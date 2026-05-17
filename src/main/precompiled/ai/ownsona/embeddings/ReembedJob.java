package ai.ownsona.embeddings;

import ai.ownsona.ApplicationIniWriter;
import ai.ownsona.Config;
import ai.ownsona.memory.MemoryRepository;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.kissweb.database.Connection;
import org.kissweb.restServer.MainServlet;

import java.util.ArrayList;
import java.util.List;

/**
 * Startup-time walker that re-embeds rows whose stored
 * {@code (embedding_provider, embedding_model)} doesn't match the active
 * config, or whose {@code embedding} is NULL (which happens after a
 * different-dim column resize).
 *
 * <p>Runs from {@code MCPServer.<clinit>} AFTER {@code DbMigrator} and
 * {@code RecordMigrator}, and only when
 * {@code REEMBED_ON_STARTUP=true} in {@code application.ini}.  On clean
 * completion (no rows left to re-embed), flips
 * {@code REEMBED_ON_STARTUP} back to {@code false} in
 * {@code application.ini} so a subsequent restart doesn't accidentally
 * re-run the walker.
 *
 * <p>Resumable by virtue of the SELECT filter: kill the JVM mid-run and
 * the next startup picks up exactly the rows still showing the old
 * provider/model, ignoring those already updated.
 *
 * <p>Per-batch failures abort the whole job (the next restart will
 * retry from the same point).  This is intentional: a partial run that
 * silently skips broken rows would leave the store in a mixed-model
 * state without operator visibility.
 */
public final class ReembedJob {

    private static final Logger logger = LogManager.getLogger(ReembedJob.class);

    /** Rows per embedBatch call.  Matches the OpenAI batch sweet spot. */
    private static final int BATCH_SIZE = 50;

    private final MemoryRepository repo;
    private final EmbeddingProvider provider;

    public ReembedJob(MemoryRepository repo, EmbeddingProvider provider) {
        this.repo = repo;
        this.provider = provider;
    }

    /**
     * Check the {@code REEMBED_ON_STARTUP} flag and, if true, walk the
     * table re-embedding stale rows.  Returns the number of rows
     * re-embedded (0 if the flag is off or the store is already current).
     *
     * <p>Throws on infrastructure failure (no DB connection, embedding
     * provider down) so the servlet's static initializer surfaces the
     * problem rather than starting up in a half-migrated state.
     */
    public int runOnStartup() {
        final String flag = lookup("REEMBED_ON_STARTUP");
        if (flag == null || !"true".equalsIgnoreCase(flag.trim())) {
            logger.info("reembed: REEMBED_ON_STARTUP not set; skipping");
            return 0;
        }

        final String activeProvider = Config.EMBEDDING_PROVIDER;
        final String activeModel    = provider.modelName();
        logger.info("reembed: starting active_provider={} active_model={} dims={}",
                activeProvider, activeModel, provider.dimensions());

        int total = 0;
        long lastId = -1;

        while (true) {
            final List<Long> ids = fetchPage(activeProvider, activeModel, lastId);
            if (ids.isEmpty())
                break;
            processBatch(ids, activeProvider, activeModel);
            total += ids.size();
            lastId = ids.get(ids.size() - 1);
            logger.info("reembed: progress count={}", total);
            // Loop terminates when fetchPage returns empty.  A partial
            // page does NOT mean "done" --- some rows in the same id
            // range could become stale again between pages if some other
            // process updates rows during the walk.  The SELECT filter
            // is the source of truth.
        }

        logger.info("reembed: done count={}", total);

        // Flip the flag off so the next restart doesn't re-run a no-op
        // walker.  Only do this on the success path; a thrown exception
        // means the operator should investigate and re-trigger.
        final boolean wrote = ApplicationIniWriter.setKey("REEMBED_ON_STARTUP", "false");
        if (!wrote)
            logger.warn("reembed: could not update REEMBED_ON_STARTUP in application.ini; " +
                    "next restart will re-run the (now no-op) walker until the file is fixed by hand");

        return total;
    }

    private List<Long> fetchPage(String activeProvider, String activeModel, long lastId) {
        final Connection db = MainServlet.openNewConnection();
        if (db == null)
            throw new IllegalStateException("reembed: openNewConnection returned null");
        boolean ok = false;
        try {
            final List<Long> ids = repo.findStaleEmbeddingIds(db, activeProvider, activeModel, lastId, BATCH_SIZE);
            ok = true;
            return ids;
        } catch (Exception e) {
            throw new IllegalStateException("reembed: id-scan failed: " + e.getMessage(), e);
        } finally {
            MainServlet.closeConnection(db, ok);
        }
    }

    /**
     * One batch: read the texts, embed them all in a single provider
     * call, write the new vectors back.  Each batch runs in its own
     * connection/transaction --- the commit happens at
     * {@code closeConnection(db, true)}, so a crash mid-batch loses at
     * most the current batch.
     */
    private void processBatch(List<Long> ids, String activeProvider, String activeModel) {
        final Connection db = MainServlet.openNewConnection();
        if (db == null)
            throw new IllegalStateException("reembed: openNewConnection returned null for batch");
        boolean ok = false;
        try {
            // 1. Read text for the ids.  Hard-deleted rows since the scan
            //    drop out of the result; we just skip them.
            final List<Object[]> pairs = repo.fetchTextsByIds(db, ids);
            if (pairs.isEmpty()) {
                ok = true;
                return;
            }
            final List<Long>   keptIds   = new ArrayList<>(pairs.size());
            final List<String> keptTexts = new ArrayList<>(pairs.size());
            for (Object[] p : pairs) {
                keptIds.add((Long) p[0]);
                keptTexts.add((String) p[1]);
            }

            // 2. One embedding round-trip for the whole batch.
            final List<float[]> vecs;
            try {
                vecs = provider.embedBatch(keptTexts);
            } catch (Exception e) {
                throw new IllegalStateException(
                        "reembed: embedBatch failed for ids " + keptIds.get(0) + ".." +
                        keptIds.get(keptIds.size() - 1) + ": " + e.getMessage(), e);
            }
            if (vecs.size() != keptIds.size())
                throw new IllegalStateException("reembed: provider returned " + vecs.size() +
                        " vectors for " + keptIds.size() + " inputs");

            // 3. Write each row's new vector + provider + model.
            for (int i = 0; i < keptIds.size(); i++) {
                final long id = keptIds.get(i);
                final float[] v = vecs.get(i);
                if (v == null || v.length != provider.dimensions())
                    throw new IllegalStateException("reembed: provider returned wrong-dim " +
                            "vector for id=" + id + " (got " + (v == null ? "null" : v.length) +
                            ", expected " + provider.dimensions() + ")");
                repo.setEmbedding(db, id, v, activeProvider, activeModel);
            }
            ok = true;
        } catch (Exception e) {
            // Wrap non-runtime exceptions so the caller's stack stays sane.
            if (e instanceof RuntimeException)
                throw (RuntimeException) e;
            throw new IllegalStateException("reembed: batch write failed: " + e.getMessage(), e);
        } finally {
            MainServlet.closeConnection(db, ok);
        }
    }

    private static String lookup(String key) {
        final Object v = MainServlet.getEnvironment(key);
        return v == null ? null : v.toString();
    }
}
