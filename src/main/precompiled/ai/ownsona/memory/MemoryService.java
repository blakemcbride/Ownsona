package ai.ownsona.memory;

import ai.ownsona.Config;
import ai.ownsona.SecretScanner;
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
                                   String rawCaptureMode, String rawSessionId) {
        final String text = requireText(rawText);
        final String secret = SecretScanner.detect(text);
        if (secret != null)
            throw new ServiceException(ServiceException.SECRET_REJECTED, secret);

        final String normalized   = TextNormalizer.normalize(text);
        final String[] tags       = cleanTags(rawTags);
        final double imp          = clampImportance(importance);
        final String captureMode  = validateCaptureMode(rawCaptureMode);
        final String sessionId    = validateSessionId(rawSessionId);
        final String metadataJson = buildMetadataJson(captureMode);

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
            return new RememberResult(id, false);
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
    public List<BatchRememberResult> rememberBatch(List<BatchRememberItem> items, String defaultProvider) {
        if (items == null || items.isEmpty())
            throw new ServiceException(ServiceException.INVALID_INPUT, "items is required and must be non-empty.");
        if (items.size() > Config.MAX_BATCH_SIZE)
            throw new ServiceException(ServiceException.LIMIT_EXCEEDED,
                    "batch too large: " + items.size() + " > " + Config.MAX_BATCH_SIZE);

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

                validIdx.add(i);
                validText.add(text);
                validNorm.add(TextNormalizer.normalize(text));
                validTags.add(tags);
                validImp.add(imp);
                validProv.add(provider);
                validMeta.add(buildMetadataJson(captureMode));
                validSession.add(sessionId);
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
                            validMeta.get(j), validSession.get(j)));
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

    public String buildContextPrompt(String userPrompt, Integer limit) {
        final String prompt = requireText(userPrompt);
        final List<MemoryRow> matches = recall(prompt, limit, null, null);
        final List<String> facts = new ArrayList<>(matches.size());
        for (MemoryRow m : matches)
            facts.add(m.text);
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

    public MemoryRow update(long id, String rawText, String[] rawTags, Double importance) {
        final String text = requireText(rawText);
        final String secret = SecretScanner.detect(text);
        if (secret != null)
            throw new ServiceException(ServiceException.SECRET_REJECTED, secret);

        final String normalized = TextNormalizer.normalize(text);
        final String[] tags = (rawTags == null) ? null : cleanTags(rawTags);

        final Connection db = MainServlet.openNewConnection();
        boolean success = false;
        try {
            final MemoryRow existing = repo.findById(db, id);
            if (existing == null || existing.deletedAt != null)
                throw new ServiceException(ServiceException.NOT_FOUND, "Memory " + id + " not found.");

            final float[] vec = embed(text);
            final boolean ok = repo.update(db, id, text, normalized, vec, tags, importance,
                    Config.EMBEDDING_PROVIDER, embedder.modelName());
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
    // forget
    // ====================================================================================

    public boolean forget(long id, boolean hardDelete) {
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
            final boolean ok = repo.softDelete(db, id);
            logger.info("forget: soft-deleted id={} ok={}", id, ok);
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
                                                 String metadataJson, String sessionId) {
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
            return BatchRememberResult.success(origIdx, id, false);
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
            if (trimmed.length() > MAX_TAG_CHARS)
                throw new ServiceException(ServiceException.INVALID_INPUT,
                        "Tag is too long: " + trimmed.length() + " > " + MAX_TAG_CHARS);
            deduped.add(trimmed);
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
