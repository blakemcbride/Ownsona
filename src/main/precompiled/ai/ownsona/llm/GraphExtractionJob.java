package ai.ownsona.llm;

import ai.ownsona.Config;
import ai.ownsona.memory.MemoryRepository;
import ai.ownsona.memory.RelationRepository;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.core.config.Configurator;
import org.kissweb.database.Connection;
import org.kissweb.json.JSONArray;
import org.kissweb.json.JSONObject;
import org.kissweb.restServer.MainServlet;

import java.util.ArrayList;
import java.util.List;

/**
 * Tier 4 phase 2 background job: extract {@code (subject, predicate,
 * object)} relation triples from memories via the generative seam and
 * store them in the {@code memory_relations} graph, enabling multi-hop
 * retrieval ({@code query_relations}).
 *
 * <p>Like the consolidation job this is scheduled by Kiss Cron
 * ({@code backend/CronTasks/crontab} &rarr; {@code ExtractRelations.groovy}
 * &rarr; {@link #runScheduled()}), is OFF by default (crontab line
 * commented, {@code GRAPH_EXTRACTION_ENABLED=false}, no-op without
 * {@code LLM_API_KEY}), and is cost-bounded: each memory is extracted at
 * most once (the {@code relations_extracted} flag), and a run processes at
 * most {@code GRAPH_EXTRACTION_MAX_MEMORIES} memories (one LLM call each),
 * making zero calls once the store is fully extracted.
 *
 * <p><strong>Known limitation (phase 2):</strong> a memory edited after
 * extraction keeps its original relations (the flag stays set).  The
 * traversal always returns the memory's <em>current</em> text, so a stale
 * edge is at worst a navigational hint, never wrong displayed content.
 * Re-extraction-on-edit is a future enhancement.
 */
public final class GraphExtractionJob {

    private static final Logger logger = LogManager.getLogger(GraphExtractionJob.class);

    static final String SYSTEM_PROMPT =
            "Extract factual (subject, predicate, object) triples from the statement below. " +
            "subject and object are entities (people, places, organizations, things, or the user); " +
            "predicate is a short relation phrase (e.g. \"lives in\", \"works at\", \"is married to\"). " +
            "Extract ONLY relations the statement clearly asserts --- do not infer or invent. " +
            "Reply with ONLY a JSON object: {\"triples\": [{\"subject\": \"...\", \"predicate\": \"...\", " +
            "\"object\": \"...\"}, ...]} --- an empty array if the statement states no clear relation. " +
            "No prose, no markdown.";

    /** Cap on triples accepted from a single memory, to bound a runaway reply. */
    private static final int MAX_TRIPLES_PER_MEMORY = 32;

    private final MemoryRepository repo;
    private final RelationRepository relRepo;
    private final GenerativeProvider llm;
    private final String userId;

    public GraphExtractionJob(MemoryRepository repo, RelationRepository relRepo,
                              GenerativeProvider llm, String userId) {
        this.repo    = repo;
        this.relRepo = relRepo;
        this.llm     = llm;
        this.userId  = userId;
    }

    /**
     * Cron entry point.  Builds its own dependencies, checks the gates, and
     * runs one extraction pass.  Never throws --- failures are logged.
     *
     * @return number of memories extracted (0 when disabled / nothing to do)
     */
    public static int runScheduled() {
        Configurator.setLevel("ai.ownsona.llm", Level.INFO);

        if (!Config.GRAPH_EXTRACTION_ENABLED) {
            logger.info("graph-extraction: GRAPH_EXTRACTION_ENABLED is false; skipping");
            return 0;
        }
        if (!Config.LLM_ENABLED) {
            logger.warn("graph-extraction: enabled but no generative provider configured " +
                    "(LLM_API_KEY unset); skipping");
            return 0;
        }
        try {
            final MemoryRepository repo = new MemoryRepository();
            final RelationRepository relRepo = new RelationRepository();
            final GenerativeProvider llm = new OpenAIGenerativeProvider(
                    Config.LLM_API_KEY, Config.LLM_MODEL, Config.LLM_ENDPOINT);
            return new GraphExtractionJob(repo, relRepo, llm, Config.OWNSONA_USER_ID).runOnce();
        } catch (Exception e) {
            logger.error("graph-extraction: run failed to start: {}", e.getMessage(), e);
            return 0;
        }
    }

    /** Run one extraction pass.  Returns the number of memories processed. */
    public int runOnce() {
        final long t0 = System.currentTimeMillis();
        logger.info("graph-extraction: starting max_memories={} model={}",
                Config.GRAPH_EXTRACTION_MAX_MEMORIES, llm.modelName());

        final List<Long> ids = fetchUnextracted(Config.GRAPH_EXTRACTION_MAX_MEMORIES);
        int processed = 0, triplesTotal = 0, failed = 0;
        for (Long id : ids) {
            try {
                triplesTotal += extractOne(id);
                processed++;
            } catch (Exception e) {
                logger.error("graph-extraction: memory id={} failed: {}", id, e.getMessage(), e);
                failed++;
            }
        }

        logger.warn("graph-extraction: done memories={} triples={} failed={} ms={}",
                processed, triplesTotal, failed, System.currentTimeMillis() - t0);
        return processed;
    }

    private List<Long> fetchUnextracted(int limit) {
        final Connection db = MainServlet.openNewConnection();
        if (db == null)
            throw new IllegalStateException("graph-extraction: openNewConnection returned null");
        boolean ok = false;
        try {
            final List<Long> ids = repo.findUnextractedMemoryIds(db, -1, limit);
            ok = true;
            return ids;
        } catch (Exception e) {
            throw new IllegalStateException("graph-extraction: id-scan failed: " + e.getMessage(), e);
        } finally {
            MainServlet.closeConnection(db, ok);
        }
    }

    /**
     * Extract one memory on its own connection/transaction: read its text,
     * ask the LLM for triples, replace its relations, and mark it extracted.
     * The whole row's work commits together (or rolls back together).
     * Returns the number of triples stored.
     */
    private int extractOne(long id) throws Exception {
        final Connection db = MainServlet.openNewConnection();
        if (db == null)
            throw new IllegalStateException("graph-extraction: openNewConnection returned null for id=" + id);
        boolean ok = false;
        try {
            final List<Object[]> pairs = repo.fetchTextsByIds(db, java.util.Collections.singletonList(id));
            if (pairs.isEmpty()) {
                // Hard-deleted since the scan; nothing to do.
                ok = true;
                return 0;
            }
            final String text = (String) pairs.get(0)[1];

            final String reply = llm.complete(SYSTEM_PROMPT, text == null ? "" : text);
            final List<String[]> triples = parseTriples(reply);

            // Replace (idempotent re-extraction): clear any prior edges first.
            relRepo.deleteByMemory(db, id);
            int stored = 0;
            for (String[] t : triples) {
                relRepo.insert(db, userId, t[0], t[1], t[2], id);
                stored++;
            }
            repo.markRelationsExtracted(db, id);
            ok = true;
            return stored;
        } finally {
            MainServlet.closeConnection(db, ok);
        }
    }

    // ====================================================================================
    // pure helpers (package-private for unit tests)
    // ====================================================================================

    /**
     * Parse the model's triples reply into a list of {@code [subject,
     * predicate, object]} arrays.  Tolerant of prose/markdown around the
     * JSON; any parse failure yields an empty list (extract nothing rather
     * than store garbage).  Triples with a blank field are dropped; the
     * list is capped at {@link #MAX_TRIPLES_PER_MEMORY}.
     */
    static List<String[]> parseTriples(String reply) {
        final List<String[]> out = new ArrayList<>();
        final String json = ConsolidationJob.extractJsonObject(reply);
        if (json == null)
            return out;
        try {
            final JSONObject o = new JSONObject(json);
            final JSONArray arr = o.getJSONArray("triples", false);
            if (arr == null)
                return out;
            for (int i = 0; i < arr.length() && out.size() < MAX_TRIPLES_PER_MEMORY; i++) {
                final JSONObject t = arr.getJSONObject(i);
                if (t == null)
                    continue;
                final String s = trimOrNull(t.getString("subject", null));
                final String p = trimOrNull(t.getString("predicate", null));
                final String ob = trimOrNull(t.getString("object", null));
                if (s != null && p != null && ob != null)
                    out.add(new String[]{s, p, ob});
            }
        } catch (Exception e) {
            return new ArrayList<>();
        }
        return out;
    }

    private static String trimOrNull(String s) {
        if (s == null)
            return null;
        final String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
