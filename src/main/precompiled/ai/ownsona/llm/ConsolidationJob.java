package ai.ownsona.llm;

import ai.ownsona.Config;
import ai.ownsona.embeddings.OpenAIEmbeddingProvider;
import ai.ownsona.memory.MemoryRepository;
import ai.ownsona.memory.MemoryRow;
import ai.ownsona.memory.MemoryService;
import ai.ownsona.memory.NearDuplicateGroup;
import ai.ownsona.memory.NearDuplicatesResult;
import ai.ownsona.memory.RememberResult;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.core.config.Configurator;
import org.kissweb.json.JSONObject;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Tier 3 consolidation ("sleep") job: periodically cluster near-duplicate
 * memories, ask the configured generative model to merge each cluster
 * into one canonical fact, store the canonical, and supersede the raw
 * originals (soft-delete + {@code replaced_by_id} link).
 *
 * <p><strong>Scheduling lives in Kiss Cron</strong>
 * ({@code backend/CronTasks/crontab} &rarr; {@code Consolidate.groovy}
 * &rarr; {@link #runScheduled()}).  This class holds only the pass logic,
 * not a timer.  It is gated three ways and does nothing unless all hold:
 * {@code CONSOLIDATION_ENABLED=true}, a generative provider is configured
 * ({@code LLM_API_KEY}), and near-duplicate clusters actually exist.
 *
 * <p>Safety:
 * <ul>
 *   <li>Clusters containing a protected ({@code keep='Y'}) memory are
 *       skipped entirely --- the job never touches anything around a
 *       memory the user locked.</li>
 *   <li>Originals are <em>superseded</em> (soft-delete + link), never hard
 *       deleted, so every merge is recoverable from the tombstone.</li>
 *   <li>The threshold defaults high (0.95) so only near-identical rows
 *       merge unattended.</li>
 *   <li>Per-cluster failures are logged and skipped; one bad cluster never
 *       aborts the run.  Each merge + the run summary are logged so the
 *       operator can audit (and undo) what happened.</li>
 * </ul>
 *
 * <p>Generative-LLM use here is allowed by design invariant #1: this is a
 * background maintenance task using the dedicated {@link GenerativeProvider}
 * seam, not a generative call on the synchronous recall/remember path.
 */
public final class ConsolidationJob {

    private static final Logger logger = LogManager.getLogger(ConsolidationJob.class);

    static final String SYSTEM_PROMPT =
            "You merge near-duplicate personal-memory facts into a single canonical fact. " +
            "You are given several short statements that a similarity search flagged as " +
            "near-duplicates of each other. If and only if they truly state the same fact " +
            "(possibly with minor wording or detail differences), merge them into ONE clear, " +
            "complete statement that preserves every distinct detail. If they are actually " +
            "different facts that merely look similar, do NOT merge them. " +
            "Reply with ONLY a JSON object: {\"merge\": true, \"text\": \"<canonical fact>\"} " +
            "to merge, or {\"merge\": false} to leave them alone. No prose, no markdown.";

    private final MemoryService service;
    private final GenerativeProvider llm;

    public ConsolidationJob(MemoryService service, GenerativeProvider llm) {
        this.service = service;
        this.llm     = llm;
    }

    /**
     * Cron entry point.  Builds its own dependencies from {@link Config},
     * checks the gates, and runs one consolidation pass.  Never throws ---
     * a background job must not crash the cron thread; failures are logged.
     *
     * @return number of clusters merged (0 when disabled / nothing to do)
     */
    public static int runScheduled() {
        // Scope the consolidation subsystem to INFO so its (low-volume,
        // data-mutating) output is visible even though ai.ownsona sits at
        // ERROR after startup.  This is the operator's audit trail.
        Configurator.setLevel("ai.ownsona.llm", Level.INFO);

        if (!Config.CONSOLIDATION_ENABLED) {
            logger.info("consolidation: CONSOLIDATION_ENABLED is false; skipping");
            return 0;
        }
        if (!Config.LLM_ENABLED) {
            logger.warn("consolidation: enabled but no generative provider configured " +
                    "(LLM_API_KEY unset); skipping");
            return 0;
        }

        try {
            final MemoryRepository repo = new MemoryRepository();
            final OpenAIEmbeddingProvider embedder = new OpenAIEmbeddingProvider(
                    Config.EMBEDDING_API_KEY, Config.EMBEDDING_MODEL, Config.EMBEDDING_DIMENSIONS);
            final MemoryService service = new MemoryService(repo, embedder);
            final GenerativeProvider llm = new OpenAIGenerativeProvider(
                    Config.LLM_API_KEY, Config.LLM_MODEL, Config.LLM_ENDPOINT);
            return new ConsolidationJob(service, llm).runOnce();
        } catch (Exception e) {
            logger.error("consolidation: run failed to start: {}", e.getMessage(), e);
            return 0;
        }
    }

    /**
     * Run one consolidation pass.  Returns the number of clusters merged.
     */
    public int runOnce() {
        final long t0 = System.currentTimeMillis();
        logger.info("consolidation: starting threshold={} max_groups={} model={}",
                Config.CONSOLIDATION_THRESHOLD, Config.CONSOLIDATION_MAX_GROUPS, llm.modelName());

        final NearDuplicatesResult dups =
                service.findNearDuplicates(Config.CONSOLIDATION_THRESHOLD, Config.CONSOLIDATION_MAX_GROUPS);

        int merged = 0, skipped = 0, failed = 0;
        for (NearDuplicateGroup group : dups.groups) {
            final List<MemoryRow> members = group.memories;
            try {
                if (members == null || members.size() < 2) {
                    skipped++;
                    continue;
                }
                if (hasProtectedMember(members)) {
                    logger.info("consolidation: skipping cluster {} (contains a protected memory)",
                            idsOf(members));
                    skipped++;
                    continue;
                }

                final String reply = llm.complete(SYSTEM_PROMPT, buildMergeUserMessage(members));
                final MergeDecision decision = parseMergeDecision(reply);
                if (!decision.merge || decision.text == null || decision.text.isEmpty()) {
                    logger.info("consolidation: model declined to merge cluster {}", idsOf(members));
                    skipped++;
                    continue;
                }

                final Long[]   supersedeIds = idArray(members);
                final String[] tags         = unionTags(members);
                final Double   importance    = maxImportance(members);

                // dedup_policy=insert: the canonical is by construction
                // similar to its sources, so skip the semantic-dedup check
                // and insert it; the sources are then superseded out of the
                // active set in the same call.
                final RememberResult r = service.remember(
                        decision.text, tags, "consolidation", importance,
                        null, null, MemoryService.DEDUP_POLICY_INSERT,
                        null, null, "consolidation-job",
                        supersedeIds, null);

                logger.warn("consolidation: merged cluster {} -> new id {} (\"{}\")",
                        idsOf(members), r.id, truncate(decision.text, 120));
                merged++;
            } catch (Exception e) {
                logger.error("consolidation: cluster {} failed: {}", idsOf(members), e.getMessage(), e);
                failed++;
            }
        }

        logger.warn("consolidation: done merged={} skipped={} failed={} clusters={} ms={}",
                merged, skipped, failed, dups.groups.size(), System.currentTimeMillis() - t0);
        return merged;
    }

    // ====================================================================================
    // pure helpers (package-private for unit tests)
    // ====================================================================================

    /** Result of parsing the model's merge reply. */
    static final class MergeDecision {
        final boolean merge;
        final String  text;
        MergeDecision(boolean merge, String text) {
            this.merge = merge;
            this.text  = text;
        }
    }

    /** Build the user message: a numbered list of the cluster's statements. */
    static String buildMergeUserMessage(List<MemoryRow> members) {
        final StringBuilder sb = new StringBuilder();
        sb.append("Near-duplicate statements:\n");
        int i = 1;
        for (MemoryRow m : members) {
            sb.append(i++).append(". ").append(m.text == null ? "" : m.text).append('\n');
        }
        return sb.toString();
    }

    /**
     * Parse the model reply into a {@link MergeDecision}.  Tolerant of
     * prose/markdown around the JSON.  Any parse failure is treated as
     * "do not merge" (the safe default --- never supersede on a garbled
     * reply).
     */
    static MergeDecision parseMergeDecision(String reply) {
        final String json = extractJsonObject(reply);
        if (json == null)
            return new MergeDecision(false, null);
        try {
            final JSONObject o = new JSONObject(json);
            final boolean merge = o.has("merge") && o.getBoolean("merge");
            final String text = o.has("text") ? o.getString("text") : null;
            if (!merge)
                return new MergeDecision(false, null);
            final String trimmed = (text == null) ? null : text.trim();
            return new MergeDecision(trimmed != null && !trimmed.isEmpty(), trimmed);
        } catch (Exception e) {
            return new MergeDecision(false, null);
        }
    }

    /** Extract the first balanced {@code {...}} JSON object substring, or null. */
    static String extractJsonObject(String s) {
        if (s == null)
            return null;
        final int start = s.indexOf('{');
        if (start < 0)
            return null;
        int depth = 0;
        boolean inStr = false;
        boolean esc = false;
        for (int i = start; i < s.length(); i++) {
            final char c = s.charAt(i);
            if (inStr) {
                if (esc)
                    esc = false;
                else if (c == '\\')
                    esc = true;
                else if (c == '"')
                    inStr = false;
                continue;
            }
            if (c == '"')
                inStr = true;
            else if (c == '{')
                depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0)
                    return s.substring(start, i + 1);
            }
        }
        return null;
    }

    static boolean hasProtectedMember(List<MemoryRow> members) {
        for (MemoryRow m : members)
            if ("Y".equals(m.keep))
                return true;
        return false;
    }

    static String[] unionTags(List<MemoryRow> members) {
        final Set<String> tags = new LinkedHashSet<>();
        for (MemoryRow m : members)
            if (m.tags != null)
                for (String t : m.tags)
                    if (t != null && !t.isEmpty())
                        tags.add(t);
        return tags.toArray(new String[0]);
    }

    static Double maxImportance(List<MemoryRow> members) {
        double max = 0.0;
        boolean any = false;
        for (MemoryRow m : members) {
            any = true;
            if (m.importance > max)
                max = m.importance;
        }
        return any ? max : null;
    }

    private static Long[] idArray(List<MemoryRow> members) {
        final Long[] ids = new Long[members.size()];
        for (int i = 0; i < members.size(); i++)
            ids[i] = members.get(i).id;
        return ids;
    }

    private static String idsOf(List<MemoryRow> members) {
        if (members == null)
            return "[]";
        final StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < members.size(); i++) {
            if (i > 0)
                sb.append(',');
            sb.append(members.get(i).id);
        }
        return sb.append(']').toString();
    }

    private static String truncate(String s, int max) {
        if (s == null || s.length() <= max)
            return s;
        return s.substring(0, max) + "...";
    }
}
