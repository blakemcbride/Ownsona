package ai.ownsona.memory;

import ai.ownsona.Config;
import ai.ownsona.SecretScanner;
import ai.ownsona.TagNormalizer;
import ai.ownsona.TextNormalizer;
import ai.ownsona.embeddings.EmbeddingProvider;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.kissweb.database.Connection;
import org.kissweb.json.JSONObject;
import org.kissweb.restServer.MainServlet;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Application-level memory operations.  Owns the embedding provider and
 * coordinates with {@link MemoryRepository} for persistence.
 *
 * <p>This layer is independent of MCP --- it returns plain Java objects so
 * unit tests don't need a live MCP transport.  The MCP servlet adapts these
 * results into the JSON shapes the spec requires.
 */
public final class MemoryService {

    private static final Logger logger = LogManager.getLogger(MemoryService.class);

    private static final int MAX_TAG_CHARS        = 64;
    private static final int MAX_TAGS             = 16;
    private static final int MAX_SESSION_ID_CHARS = 256;
    private static final String UNIQUE_VIOLATION_SQLSTATE = "23505";

    private static final String CAPTURE_EXPLICIT = "explicit";
    private static final String CAPTURE_INFERRED = "inferred";

    /** Dedup-on-write policies (Phase 4). */
    static final String DEDUP_POLICY_INSERT       = "insert";
    static final String DEDUP_POLICY_SKIP_IF_NEAR = "skip_if_near";
    static final String DEDUP_POLICY_ASK          = "ask";

    /**
     * Similarity threshold above which the dedup-on-write check
     * considers an existing row a near-duplicate.  Tuned later based
     * on telemetry.
     */
    private static final double DEDUP_THRESHOLD = 0.90;

    /** Top-K candidates the dedup check considers. */
    private static final int DEDUP_TOPK = 5;

    /**
     * Hard cap on how far in the future expires_at may be set
     * (100 years).  Catches fat-finger inputs.
     */
    private static final long MAX_EXPIRES_AT_FUTURE_MS = 100L * 365L * 24L * 60L * 60L * 1000L;

    /**
     * Tolerance for last_confirmed_at relative to "now" (5 minutes).
     * Allows for benign client/server clock skew without accepting an
     * obviously-in-the-future timestamp.
     */
    private static final long LAST_CONFIRMED_AT_FUTURE_TOLERANCE_MS = 5L * 60L * 1000L;

    /** Cap on the forget_reason free-text field stored on tombstones. */
    private static final int MAX_FORGET_REASON_CHARS = 1024;

    private final MemoryRepository repo;
    private final EmbeddingProvider embedder;
    private final String userId;

    public MemoryService(MemoryRepository repo, EmbeddingProvider embedder) {
        this.repo     = repo;
        this.embedder = embedder;
        this.userId   = Config.OWNSONA_USER_ID;
    }

    // ====================================================================================
    // remember
    // ====================================================================================

    public RememberResult remember(String rawText, String[] rawTags, String sourceProvider, Double importance,
                                   String rawCaptureMode, String rawSessionId,
                                   String rawDedupPolicy,
                                   java.util.Date rawExpiresAt, java.util.Date rawLastConfirmedAt) {
        final String text = requireText(rawText);
        final String secret = SecretScanner.detect(text);
        if (secret != null)
            throw new ServiceException(ServiceException.SECRET_REJECTED, secret);

        final String normalized        = TextNormalizer.normalize(text);
        final String[] tags            = cleanTags(rawTags);
        final double imp               = clampImportance(importance);
        final String captureMode       = validateCaptureMode(rawCaptureMode);
        final String sessionId         = validateSessionId(rawSessionId);
        final String metadataJson      = buildMetadataJson(captureMode);
        final String dedupPolicy       = validateDedupPolicy(rawDedupPolicy);
        final java.util.Date expiresAt = validateExpiresAt(rawExpiresAt);
        final java.util.Date lastConfirmedAt = validateLastConfirmedAt(rawLastConfirmedAt);

        final Connection db = MainServlet.openNewConnection();
        boolean success = false;
        try {
            final Long existing = repo.findActiveIdByNormalized(db, userId, normalized);
            if (existing != null) {
                logger.info("remember: duplicate, returning existing id={}", existing);
                success = true;
                return new RememberResult(existing, true);
            }

            final float[] vec = embed(text);

            // Semantic dedup check (unless caller opted out with policy=insert).
            // Two passes: one over active rows ("candidates") and one over
            // tombstones ("previouslyCorrected") so the response can warn
            // the caller about facts the user already forgot.  For
            // skip_if_near: if an active near-dup exists, return its id
            // without inserting.  Tombstones alone don't block the insert
            // --- they're a louder signal, not a refusal.
            final List<MemoryRow> candidates = findDedupCandidates(db, vec, dedupPolicy);
            final List<MemoryRow> previouslyCorrected = findPreviouslyCorrected(db, vec, dedupPolicy);
            if (!candidates.isEmpty() && DEDUP_POLICY_SKIP_IF_NEAR.equals(dedupPolicy)) {
                final MemoryRow top = candidates.get(0);
                logger.info("remember: near-dup found id={} score={} policy=skip_if_near",
                        top.id, top.score);
                success = true;
                return new RememberResult(top.id, true, candidates, previouslyCorrected);
            }
            if (!previouslyCorrected.isEmpty())
                logger.info("remember: previously-corrected near-dup found id={} score={} (proceeding with insert)",
                        previouslyCorrected.get(0).id, previouslyCorrected.get(0).score);

            final MemoryInsert ins = new MemoryInsert();
            ins.userId               = userId;
            ins.text                 = text;
            ins.normalizedText       = normalized;
            ins.embedding            = vec;
            ins.tags                 = tags;
            ins.importance           = imp;
            ins.sourceProvider       = sourceProvider;
            ins.sourceConversationId = sessionId;
            ins.embeddingProvider    = Config.EMBEDDING_PROVIDER;
            ins.embeddingModel       = embedder.modelName();
            ins.metadataJson         = metadataJson;
            ins.recordVersion        = RecordUpgraderRegistry.CURRENT_RECORD_VERSION;
            ins.expiresAt            = expiresAt;
            ins.lastConfirmedAt      = lastConfirmedAt;

            final long id;
            try {
                id = repo.insert(db, ins);
            } catch (Exception e) {
                if (isUniqueViolation(e)) {
                    final Long racedId = repo.findActiveIdByNormalized(db, userId, normalized);
                    if (racedId != null) {
                        logger.info("remember: lost insert race, returning existing id={}", racedId);
                        success = true;
                        return new RememberResult(racedId, true);
                    }
                }
                throw e;
            }
            logger.info("remember: inserted id={} chars={} tags={}", id, text.length(), tags.length);
            success = true;
            return new RememberResult(id, false, candidates, previouslyCorrected);
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            throw wrap(e, "remember failed");
        } finally {
            MainServlet.closeConnection(db, success);
        }
    }

    // ====================================================================================
    // remember_batch
    // ====================================================================================

    /**
     * Bulk version of {@link #remember}.
     *
     * <p>Validation errors, secret rejections, and per-row insert failures
     * are recorded in the corresponding {@link BatchRememberResult} rather
     * than thrown --- one bad item does not fail the rest of the batch.
     * The single failure mode that aborts the whole call is an embedding
     * provider error: if the OpenAI call fails, every otherwise-valid item
     * gets marked {@code EMBEDDING_ERROR}.
     *
     * <p>The whole batch is embedded with a single
     * {@link EmbeddingProvider#embedBatch} call (one HTTP round-trip on
     * OpenAI), then inserts run sequentially on a single pooled JDBC
     * connection.  For a 100-item bulk import this drops wall-clock time
     * from minutes to seconds.
     */
    public List<BatchRememberResult> rememberBatch(List<BatchRememberItem> items, String defaultProvider,
                                                   String rawDefaultDedupPolicy) {
        if (items == null || items.isEmpty())
            throw new ServiceException(ServiceException.INVALID_INPUT, "items is required and must be non-empty.");
        if (items.size() > Config.MAX_BATCH_SIZE)
            throw new ServiceException(ServiceException.LIMIT_EXCEEDED,
                    "batch too large: " + items.size() + " > " + Config.MAX_BATCH_SIZE);

        // Validated batch-level default for dedup_policy.  Each item can
        // override; otherwise this applies.
        final String defaultDedupPolicy = validateDedupPolicy(rawDefaultDedupPolicy);

        // Pre-allocate one slot per input so we can fill in any order.
        final List<BatchRememberResult> results = new ArrayList<>(Collections.nCopies(items.size(), null));

        // Phase 1: validate. Failures fill in the per-input result and
        // exclude the item from the embedding call.
        final List<Integer>  validIdx     = new ArrayList<>();
        final List<String>   validText    = new ArrayList<>();
        final List<String>   validNorm    = new ArrayList<>();
        final List<String[]> validTags    = new ArrayList<>();
        final List<Double>   validImp     = new ArrayList<>();
        final List<String>   validProv    = new ArrayList<>();
        final List<String>   validMeta    = new ArrayList<>();
        final List<String>   validSession = new ArrayList<>();
        final List<String>   validDedup   = new ArrayList<>();
        final List<java.util.Date>   validExp = new ArrayList<>();
        final List<java.util.Date>   validConfirmed = new ArrayList<>();

        for (int i = 0; i < items.size(); i++) {
            final BatchRememberItem item = items.get(i);
            try {
                if (item == null)
                    throw new ServiceException(ServiceException.INVALID_INPUT, "items[" + i + "] is null");
                final String text = requireText(item.text);
                final String secret = SecretScanner.detect(text);
                if (secret != null)
                    throw new ServiceException(ServiceException.SECRET_REJECTED, secret);
                final String[] tags = cleanTags(item.tags);
                final double imp = clampImportance(item.importance);
                final String provider = (item.sourceProvider != null && !item.sourceProvider.isEmpty())
                        ? item.sourceProvider : defaultProvider;
                final String captureMode = validateCaptureMode(item.captureMode);
                final String sessionId   = validateSessionId(item.sessionId);
                // Per-item dedup_policy overrides batch default; if neither
                // is set we keep the validated default (which itself
                // defaults to "ask" when both are null/empty).
                final String dedupPolicy = (item.dedupPolicy != null && !item.dedupPolicy.isEmpty())
                        ? validateDedupPolicy(item.dedupPolicy) : defaultDedupPolicy;
                final java.util.Date itemExpires   = validateExpiresAt(item.expiresAt);
                final java.util.Date itemConfirmed = validateLastConfirmedAt(item.lastConfirmedAt);

                validIdx.add(i);
                validText.add(text);
                validNorm.add(TextNormalizer.normalize(text));
                validTags.add(tags);
                validImp.add(imp);
                validProv.add(provider);
                validMeta.add(buildMetadataJson(captureMode));
                validSession.add(sessionId);
                validDedup.add(dedupPolicy);
                validExp.add(itemExpires);
                validConfirmed.add(itemConfirmed);
            } catch (ServiceException e) {
                results.set(i, BatchRememberResult.failure(i, e.getCode(), e.getMessage()));
            }
        }

        if (validText.isEmpty()) {
            logger.info("rememberBatch: total={} all-invalid (no embedding call made)", items.size());
            return results;
        }

        // Phase 2: one embedding call for the whole valid set.
        final List<float[]> vectors;
        try {
            vectors = embedder.embedBatch(validText);
        } catch (Exception e) {
            // Mark every still-pending valid item as embedding failed.
            for (int origIdx : validIdx) {
                if (results.get(origIdx) == null)
                    results.set(origIdx, BatchRememberResult.failure(origIdx,
                            ServiceException.EMBEDDING_ERROR,
                            "embedding provider call failed: " + e.getMessage()));
            }
            return results;
        }
        if (vectors.size() != validText.size())
            throw new ServiceException(ServiceException.EMBEDDING_ERROR,
                    "embedder returned " + vectors.size() + " vectors for " + validText.size() + " inputs");

        // Phase 3: dup-check + insert per item, on a single connection so
        // c3p0 doesn't ping-pong and currval() stays in the same session.
        // Each item gets its own savepoint so a unique-violation in one item
        // doesn't poison the rest of the batch's transaction.
        final Connection db = MainServlet.openNewConnection();
        if (db == null) {
            for (int origIdx : validIdx) {
                if (results.get(origIdx) == null)
                    results.set(origIdx, BatchRememberResult.failure(origIdx,
                            ServiceException.DATABASE_ERROR, "pool acquire failed"));
            }
        } else {
            boolean success = false;
            try {
                final java.sql.Connection sconn = db.getSQLConnection();
                for (int j = 0; j < validIdx.size(); j++) {
                    final int    origIdx = validIdx.get(j);
                    final String text    = validText.get(j);
                    final String norm    = validNorm.get(j);
                    results.set(origIdx, processBatchItem(db, sconn, origIdx, text, norm,
                            vectors.get(j), validTags.get(j), validImp.get(j), validProv.get(j),
                            validMeta.get(j), validSession.get(j),
                            validDedup.get(j), validExp.get(j), validConfirmed.get(j)));
                }
                success = true;
            } finally {
                MainServlet.closeConnection(db, success);
            }
        }

        int inserted = 0, dups = 0, errs = 0;
        for (BatchRememberResult r : results) {
            if (r == null) continue;
            if (r.ok) {
                if (r.alreadyExisted) dups++;
                else inserted++;
            } else {
                errs++;
            }
        }
        logger.info("rememberBatch: total={} inserted={} duplicates={} errors={}",
                items.size(), inserted, dups, errs);
        return results;
    }

    // ====================================================================================
    // recall
    // ====================================================================================

    public List<MemoryRow> recall(String query, Integer limit, Double minScore, String[] tags) {
        final String q = requireText(query);
        final int n = clampLimit(limit, Config.DEFAULT_RECALL_LIMIT);
        final String[] tagFilter = cleanTags(tags);

        final long t0 = System.currentTimeMillis();
        final Connection db = MainServlet.openNewConnection();
        boolean success = false;
        try {
            final float[] vec = embed(q);
            final List<MemoryRow> raw = repo.findSimilar(db, userId, vec, n, tagFilter);
            final List<MemoryRow> filtered;
            if (minScore != null) {
                filtered = new ArrayList<>(raw.size());
                for (MemoryRow m : raw)
                    if (m.score >= minScore)
                        filtered.add(m);
            } else {
                filtered = raw;
            }
            logger.info("recall: query_chars={} returned={} of_top={} ms={}",
                    q.length(), filtered.size(), raw.size(), System.currentTimeMillis() - t0);
            success = true;
            return filtered;
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            throw wrap(e, "recall failed");
        } finally {
            MainServlet.closeConnection(db, success);
        }
    }

    // ====================================================================================
    // build_context_prompt
    // ====================================================================================

    public String buildContextPrompt(String userPrompt, Integer limit, Integer maxChars) {
        final String prompt = requireText(userPrompt);
        final Integer budget = validateMaxChars(maxChars);
        final List<MemoryRow> matches = recall(prompt, limit, null, null);
        final List<String> facts = selectFactsByCharBudget(matches, budget);
        return PromptFormatter.build(prompt, facts);
    }

    // ====================================================================================
    // list_memories
    // ====================================================================================

    public List<MemoryRow> list(Integer limit, Integer offset, boolean includeDeleted) {
        final int n = clampLimit(limit, 20);
        final int off = (offset == null || offset < 0) ? 0 : offset;
        final Connection db = MainServlet.openNewConnection();
        boolean success = false;
        try {
            final List<MemoryRow> rows = repo.listRecent(db, userId, n, off, includeDeleted);
            success = true;
            return rows;
        } catch (Exception e) {
            throw wrap(e, "list failed");
        } finally {
            MainServlet.closeConnection(db, success);
        }
    }

    // ====================================================================================
    // update_memory
    // ====================================================================================

    public MemoryRow update(long id, String rawText, String[] rawTags, Double importance,
                            java.util.Date rawExpiresAt, java.util.Date rawLastConfirmedAt) {
        final String text = requireText(rawText);
        final String secret = SecretScanner.detect(text);
        if (secret != null)
            throw new ServiceException(ServiceException.SECRET_REJECTED, secret);

        final String normalized = TextNormalizer.normalize(text);
        final String[] tags = (rawTags == null) ? null : cleanTags(rawTags);
        final java.util.Date expiresAt = validateExpiresAt(rawExpiresAt);
        final java.util.Date lastConfirmedAt = validateLastConfirmedAt(rawLastConfirmedAt);

        final Connection db = MainServlet.openNewConnection();
        boolean success = false;
        try {
            final MemoryRow existing = repo.findById(db, id);
            if (existing == null || existing.deletedAt != null)
                throw new ServiceException(ServiceException.NOT_FOUND, "Memory " + id + " not found.");

            final float[] vec = embed(text);
            final boolean ok = repo.update(db, id, text, normalized, vec, tags, importance,
                    Config.EMBEDDING_PROVIDER, embedder.modelName(),
                    expiresAt, lastConfirmedAt);
            if (!ok)
                throw new ServiceException(ServiceException.NOT_FOUND, "Memory " + id + " not found.");

            final MemoryRow updated = repo.findById(db, id);
            logger.info("update: id={} chars={}", id, text.length());
            success = true;
            return updated;
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            throw wrap(e, "update failed");
        } finally {
            MainServlet.closeConnection(db, success);
        }
    }

    // ====================================================================================
    // confirm
    // ====================================================================================

    /**
     * Refresh the row's {@code last_confirmed_at} timestamp to "now"
     * without rebuilding the embedding or touching any other field.
     * Used to mark a fact as still-relevant.
     */
    public MemoryRow confirm(long id) {
        final Connection db = MainServlet.openNewConnection();
        boolean success = false;
        try {
            final MemoryRow existing = repo.findById(db, id);
            if (existing == null || existing.deletedAt != null)
                throw new ServiceException(ServiceException.NOT_FOUND, "Memory " + id + " not found.");

            final boolean ok = repo.confirm(db, id);
            if (!ok)
                throw new ServiceException(ServiceException.NOT_FOUND, "Memory " + id + " not found.");

            final MemoryRow updated = repo.findById(db, id);
            logger.info("confirm: id={}", id);
            success = true;
            return updated;
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            throw wrap(e, "confirm failed");
        } finally {
            MainServlet.closeConnection(db, success);
        }
    }

    // ====================================================================================
    // forget
    // ====================================================================================

    public boolean forget(long id, boolean hardDelete, String rawReason, Long replacedById) {
        final String reason = validateForgetReason(rawReason);
        // A hard delete drops the row entirely --- there's nowhere to store
        // the tombstone metadata.  Reject the combination so the caller
        // can't accidentally lose information they thought they were
        // recording.
        if (hardDelete && (reason != null || replacedById != null))
            throw new ServiceException(ServiceException.INVALID_INPUT,
                    "hard_delete drops the row; reason and replaced_by_id can't be recorded. " +
                    "Use soft delete (hard_delete=false) if you want a tombstone.");

        final Connection db = MainServlet.openNewConnection();
        boolean success = false;
        try {
            if (hardDelete) {
                final boolean ok = repo.hardDelete(db, id);
                if (!ok)
                    throw new ServiceException(ServiceException.NOT_FOUND, "Memory " + id + " not found.");
                logger.info("forget: hard-deleted id={}", id);
                success = true;
                return true;
            }
            final MemoryRow existing = repo.findById(db, id);
            if (existing == null)
                throw new ServiceException(ServiceException.NOT_FOUND, "Memory " + id + " not found.");
            final boolean ok = repo.softDelete(db, id, reason, replacedById);
            logger.info("forget: soft-deleted id={} ok={} reason={} replaced_by={}",
                    id, ok, reason != null, replacedById);
            success = true;
            return ok;
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            throw wrap(e, "forget failed");
        } finally {
            MainServlet.closeConnection(db, success);
        }
    }

    // ====================================================================================
    // text_search
    // ====================================================================================

    public List<MemoryRow> textSearch(String text, Integer limit) {
        final String q = requireText(text);
        final int n = clampLimit(limit, 20);
        final Connection db = MainServlet.openNewConnection();
        boolean success = false;
        try {
            final List<MemoryRow> rows = repo.textSearch(db, userId, q, n);
            success = true;
            return rows;
        } catch (Exception e) {
            throw wrap(e, "text_search failed");
        } finally {
            MainServlet.closeConnection(db, success);
        }
    }

    // ====================================================================================
    // helpers
    // ====================================================================================

    /**
     * Run one item of a batch on a shared connection.  Each item is bracketed
     * by a savepoint so a unique-violation (or any per-item failure) does not
     * poison the surrounding transaction --- the rollback to savepoint
     * un-aborts the transaction so the next item can run normally.
     */
    private BatchRememberResult processBatchItem(Connection db, java.sql.Connection sconn,
                                                 int origIdx, String text, String norm,
                                                 float[] vec, String[] tags, double imp, String provider,
                                                 String metadataJson, String sessionId,
                                                 String dedupPolicy,
                                                 java.util.Date expiresAt,
                                                 java.util.Date lastConfirmedAt) {
        final java.sql.Savepoint sp;
        try {
            sp = sconn.setSavepoint();
        } catch (SQLException e) {
            return BatchRememberResult.failure(origIdx,
                    ServiceException.DATABASE_ERROR, "savepoint failed: " + e.getMessage());
        }
        try {
            final Long existing = repo.findActiveIdByNormalized(db, userId, norm);
            if (existing != null) {
                sconn.releaseSavepoint(sp);
                return BatchRememberResult.success(origIdx, existing, true);
            }

            // Semantic dedup check (unless caller opted out with policy=insert).
            // Two passes: active rows + tombstones.  See remember() above
            // for the same shape.
            final List<MemoryRow> candidates = findDedupCandidates(db, vec, dedupPolicy);
            final List<MemoryRow> previouslyCorrected = findPreviouslyCorrected(db, vec, dedupPolicy);
            if (!candidates.isEmpty() && DEDUP_POLICY_SKIP_IF_NEAR.equals(dedupPolicy)) {
                sconn.releaseSavepoint(sp);
                return BatchRememberResult.success(origIdx, candidates.get(0).id, true,
                        candidates, previouslyCorrected);
            }

            final MemoryInsert ins = new MemoryInsert();
            ins.userId               = userId;
            ins.text                 = text;
            ins.normalizedText       = norm;
            ins.embedding            = vec;
            ins.tags                 = tags;
            ins.importance           = imp;
            ins.sourceProvider       = provider;
            ins.sourceConversationId = sessionId;
            ins.embeddingProvider    = Config.EMBEDDING_PROVIDER;
            ins.embeddingModel       = embedder.modelName();
            ins.metadataJson         = metadataJson;
            ins.recordVersion        = RecordUpgraderRegistry.CURRENT_RECORD_VERSION;
            ins.expiresAt            = expiresAt;
            ins.lastConfirmedAt      = lastConfirmedAt;

            long id;
            try {
                id = repo.insert(db, ins);
            } catch (Exception e) {
                sconn.rollback(sp);
                if (isUniqueViolation(e)) {
                    final Long racedId = repo.findActiveIdByNormalized(db, userId, norm);
                    if (racedId != null) {
                        sconn.releaseSavepoint(sp);
                        return BatchRememberResult.success(origIdx, racedId, true);
                    }
                }
                sconn.releaseSavepoint(sp);
                return BatchRememberResult.failure(origIdx,
                        ServiceException.DATABASE_ERROR, "insert failed: " + e.getMessage());
            }
            sconn.releaseSavepoint(sp);
            return BatchRememberResult.success(origIdx, id, false, candidates, previouslyCorrected);
        } catch (ServiceException e) {
            try { sconn.rollback(sp); sconn.releaseSavepoint(sp); } catch (SQLException ignore) {}
            return BatchRememberResult.failure(origIdx, e.getCode(), e.getMessage());
        } catch (Exception e) {
            try { sconn.rollback(sp); sconn.releaseSavepoint(sp); } catch (SQLException ignore) {}
            return BatchRememberResult.failure(origIdx,
                    ServiceException.DATABASE_ERROR, "insert failed: " + e.getMessage());
        }
    }

    private float[] embed(String text) {
        try {
            return embedder.embed(text);
        } catch (Exception e) {
            throw new ServiceException(ServiceException.EMBEDDING_ERROR,
                    "Embedding provider call failed: " + e.getMessage(), e);
        }
    }

    private static String requireText(String raw) {
        final String cleaned = TextNormalizer.clean(raw);
        if (cleaned == null || cleaned.isEmpty())
            throw new ServiceException(ServiceException.INVALID_INPUT, "The text field is required.");
        if (cleaned.length() > Config.MAX_TEXT_CHARS)
            throw new ServiceException(ServiceException.INVALID_INPUT,
                    "Text is too long (" + cleaned.length() + " > " + Config.MAX_TEXT_CHARS + " chars).");
        return cleaned;
    }

    private static String[] cleanTags(String[] raw) {
        if (raw == null)
            return new String[0];
        final Set<String> deduped = new LinkedHashSet<>();
        for (String t : raw) {
            if (t == null)
                continue;
            final String trimmed = t.trim();
            if (trimmed.isEmpty())
                continue;
            // Length check uses the user's input length, not the post-
            // normalization length --- the limit is on what the caller
            // is allowed to submit, not what it maps to.
            if (trimmed.length() > MAX_TAG_CHARS)
                throw new ServiceException(ServiceException.INVALID_INPUT,
                        "Tag is too long: " + trimmed.length() + " > " + MAX_TAG_CHARS);
            // Normalize before dedup so synonyms collapsing to the same
            // canonical (e.g. "tech" and "Software") yield one tag.
            final String normalized = TagNormalizer.normalize(trimmed);
            deduped.add(normalized);
            if (deduped.size() > MAX_TAGS)
                throw new ServiceException(ServiceException.INVALID_INPUT,
                        "Too many tags (max " + MAX_TAGS + ").");
        }
        return deduped.toArray(new String[0]);
    }

    private static int clampLimit(Integer requested, int defaultLimit) {
        if (requested == null)
            return defaultLimit;
        if (requested < 1)
            throw new ServiceException(ServiceException.INVALID_INPUT, "limit must be >= 1.");
        if (requested > Config.MAX_RECALL_LIMIT)
            throw new ServiceException(ServiceException.LIMIT_EXCEEDED,
                    "limit exceeds maximum of " + Config.MAX_RECALL_LIMIT + ".");
        return requested;
    }

    private static double clampImportance(Double imp) {
        if (imp == null)
            return 0.5;
        if (imp < 0.0 || imp > 1.0)
            throw new ServiceException(ServiceException.INVALID_INPUT, "importance must be between 0 and 1.");
        return imp;
    }

    /**
     * capture_mode is a small enum: "explicit" means the user told the LLM to
     * remember; "inferred" means the LLM chose to save without an explicit ask.
     * null/empty means the client did not supply a value --- we don't guess.
     *
     * <p>Package-private for unit tests in the same package.
     */
    static String validateCaptureMode(String raw) {
        if (raw == null)
            return null;
        final String trimmed = raw.trim();
        if (trimmed.isEmpty())
            return null;
        if (!CAPTURE_EXPLICIT.equals(trimmed) && !CAPTURE_INFERRED.equals(trimmed))
            throw new ServiceException(ServiceException.INVALID_INPUT,
                    "capture_mode must be '" + CAPTURE_EXPLICIT + "' or '" + CAPTURE_INFERRED + "'.");
        return trimmed;
    }

    static String validateSessionId(String raw) {
        if (raw == null)
            return null;
        final String trimmed = raw.trim();
        if (trimmed.isEmpty())
            return null;
        if (trimmed.length() > MAX_SESSION_ID_CHARS)
            throw new ServiceException(ServiceException.INVALID_INPUT,
                    "session_id is too long (" + trimmed.length() + " > " + MAX_SESSION_ID_CHARS + ").");
        return trimmed;
    }

    /**
     * Build the JSONB blob written to memories.metadata.  Returns null when
     * no fields are set --- the repository maps null → '{}'.
     */
    static String buildMetadataJson(String captureMode) {
        if (captureMode == null)
            return null;
        final JSONObject o = new JSONObject();
        o.put("capture_mode", captureMode);
        return o.toString();
    }

    /**
     * Validate the dedup_policy for remember / remember_batch (Phase 4).
     * null / empty defaults to "ask".  Any other value must be one of
     * "insert", "skip_if_near", or "ask".
     *
     * <p>Package-private for unit tests.
     */
    static String validateDedupPolicy(String raw) {
        if (raw == null)
            return DEDUP_POLICY_ASK;
        final String trimmed = raw.trim();
        if (trimmed.isEmpty())
            return DEDUP_POLICY_ASK;
        if (!DEDUP_POLICY_INSERT.equals(trimmed)
                && !DEDUP_POLICY_SKIP_IF_NEAR.equals(trimmed)
                && !DEDUP_POLICY_ASK.equals(trimmed))
            throw new ServiceException(ServiceException.INVALID_INPUT,
                    "dedup_policy must be one of '" + DEDUP_POLICY_INSERT + "', '" +
                    DEDUP_POLICY_SKIP_IF_NEAR + "', or '" + DEDUP_POLICY_ASK +
                    "' (got: " + trimmed + ").");
        return trimmed;
    }

    /**
     * Validate an optional expires_at.  null is fine (no expiration set).
     * Far-future values are rejected as fat-finger.  Past values are
     * allowed (a row can be already-expired by intent).
     *
     * <p>Package-private for unit tests.
     */
    static java.util.Date validateExpiresAt(java.util.Date raw) {
        if (raw == null)
            return null;
        final long now = System.currentTimeMillis();
        if (raw.getTime() > now + MAX_EXPIRES_AT_FUTURE_MS)
            throw new ServiceException(ServiceException.INVALID_INPUT,
                    "expires_at is too far in the future (more than 100 years out).");
        return raw;
    }

    /**
     * Validate an optional last_confirmed_at.  null is fine.  Past values
     * are allowed (a client may be backfilling).  Future values beyond
     * a small clock-skew tolerance are rejected.
     *
     * <p>Package-private for unit tests.
     */
    static java.util.Date validateLastConfirmedAt(java.util.Date raw) {
        if (raw == null)
            return null;
        final long now = System.currentTimeMillis();
        if (raw.getTime() > now + LAST_CONFIRMED_AT_FUTURE_TOLERANCE_MS)
            throw new ServiceException(ServiceException.INVALID_INPUT,
                    "last_confirmed_at must not be in the future.");
        return raw;
    }

    /**
     * Run a top-K similarity query against the given vector and return
     * rows whose score is above the dedup threshold.  Used by the
     * dedup-on-write check.  Returns empty for {@code dedup_policy =
     * insert} (caller is asking us to skip the check).  Any DB error
     * is logged at WARN but does not fail the caller --- the insert
     * proceeds without the candidates hint.
     */
    private List<MemoryRow> findDedupCandidates(Connection db, float[] vec, String dedupPolicy) {
        if (DEDUP_POLICY_INSERT.equals(dedupPolicy))
            return Collections.emptyList();
        try {
            final List<MemoryRow> hits = repo.findSimilar(db, userId, vec, DEDUP_TOPK, null);
            final List<MemoryRow> filtered = new ArrayList<>(hits.size());
            for (MemoryRow r : hits)
                if (r.score >= DEDUP_THRESHOLD)
                    filtered.add(r);
            return filtered;
        } catch (Exception e) {
            logger.warn("dedup check failed: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Tombstone-side counterpart of {@link #findDedupCandidates}: returns
     * soft-deleted rows whose embedding is near the new memory above the
     * dedup threshold.  Surfaces previously-corrected facts so the caller
     * can notice it's about to re-add something the user already forgot.
     *
     * <p>Returns empty for {@code dedup_policy = "insert"} (the caller has
     * opted out of all dedup checks).  DB failures are logged at WARN and
     * yield an empty list --- the insert proceeds without the warning.
     */
    private List<MemoryRow> findPreviouslyCorrected(Connection db, float[] vec, String dedupPolicy) {
        if (DEDUP_POLICY_INSERT.equals(dedupPolicy))
            return Collections.emptyList();
        try {
            final List<MemoryRow> hits = repo.findSimilarTombstones(db, userId, vec, DEDUP_TOPK);
            final List<MemoryRow> filtered = new ArrayList<>(hits.size());
            for (MemoryRow r : hits)
                if (r.score >= DEDUP_THRESHOLD)
                    filtered.add(r);
            return filtered;
        } catch (Exception e) {
            logger.warn("tombstone dedup check failed: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Validate a forget_reason free-text field.  null / empty stays null;
     * trimmed; bounded to {@link #MAX_FORGET_REASON_CHARS} so a runaway
     * client can't park a giant blob on a soft-deleted row.
     *
     * <p>Package-private for unit tests.
     */
    static String validateForgetReason(String raw) {
        if (raw == null)
            return null;
        final String trimmed = raw.trim();
        if (trimmed.isEmpty())
            return null;
        if (trimmed.length() > MAX_FORGET_REASON_CHARS)
            throw new ServiceException(ServiceException.INVALID_INPUT,
                    "forget_reason is too long (" + trimmed.length() + " > " +
                    MAX_FORGET_REASON_CHARS + ").");
        return trimmed;
    }

    /**
     * Validate the optional max_chars budget on build_context_prompt.
     * null means "no budget".  Zero or negative are rejected.  Char count
     * is used as a tokenizer-free proxy (vendor neutrality, guardrail #9).
     *
     * <p>Package-private for unit tests in the same package.
     */
    static Integer validateMaxChars(Integer raw) {
        if (raw == null)
            return null;
        if (raw <= 0)
            throw new ServiceException(ServiceException.INVALID_INPUT,
                    "max_chars must be > 0 (got " + raw + ").");
        return raw;
    }

    /**
     * Walk the ranked match list (most-similar first) and accumulate fact
     * texts up to the char budget.  Stops at the first row whose text
     * would push the running total over the budget --- subsequent rows
     * are discarded, regardless of length.  Stopping (rather than skipping
     * just the oversize fact and continuing) is intentional: facts are
     * ranked by similarity, so a later row is by definition less relevant
     * than any already included.
     *
     * <p>{@code null} budget means "no budget"; all facts are included.
     *
     * <p>Package-private for unit tests in the same package.
     */
    static List<String> selectFactsByCharBudget(List<MemoryRow> matches, Integer maxChars) {
        if (matches == null || matches.isEmpty())
            return Collections.emptyList();
        final List<String> out = new ArrayList<>(matches.size());
        if (maxChars == null) {
            for (MemoryRow m : matches)
                if (m != null && m.text != null)
                    out.add(m.text);
            return out;
        }
        int total = 0;
        for (MemoryRow m : matches) {
            if (m == null || m.text == null)
                continue;
            final int len = m.text.length();
            if (total + len > maxChars)
                break;
            out.add(m.text);
            total += len;
        }
        return out;
    }

    private static boolean isUniqueViolation(Throwable e) {
        Throwable cur = e;
        while (cur != null) {
            if (cur instanceof SQLException) {
                final String state = ((SQLException) cur).getSQLState();
                if (UNIQUE_VIOLATION_SQLSTATE.equals(state))
                    return true;
            }
            cur = cur.getCause();
        }
        return false;
    }

    private static ServiceException wrap(Throwable e, String message) {
        return new ServiceException(ServiceException.DATABASE_ERROR, message + ": " + e.getMessage(), e);
    }
}
