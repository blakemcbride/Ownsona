package ai.ownsona.memory;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.kissweb.database.Connection;
import org.kissweb.restServer.MainServlet;

import java.util.List;

/**
 * Startup-time per-row walker that brings every {@code memories} row up
 * to {@link RecordUpgraderRegistry#CURRENT_RECORD_VERSION} by applying
 * registered {@link RecordUpgrader}s in order.
 *
 * <p>Runs from {@code MCPServer.<clinit>} AFTER the
 * {@code DbMigrator.runOnStartup()} call --- the {@code record_version}
 * column must exist before the walker can query it.
 *
 * <p>Per-row failures are isolated and logged but do not block startup
 * (rollout plan guardrail #10).  The next startup will retry the same
 * rows from the same point.
 *
 * <p>Pagination is by ascending id; chunks of {@link #CHUNK_SIZE}.  No
 * user_id filter --- the walker upgrades data shape across all users,
 * not per-user.
 */
public final class RecordMigrator {

    private static final Logger logger = LogManager.getLogger(RecordMigrator.class);

    private static final int CHUNK_SIZE = 500;

    private final MemoryRepository repo;

    public RecordMigrator(MemoryRepository repo) {
        this.repo = repo;
    }

    /**
     * Run the walker.  Throws {@link IllegalStateException} on registry
     * misconfiguration (programmer error, fail fast).  Per-row failures
     * are logged and counted but never thrown.
     */
    public void runOnStartup() {
        RecordUpgraderRegistry.validate();
        final int target = RecordUpgraderRegistry.CURRENT_RECORD_VERSION;

        logger.info("record_migrator: starting target_version={}", target);

        // Empty registry / target == 1: no rows can be below 1, so the
        // walker has nothing to do.  Short-circuit for clarity in the log.
        if (RecordUpgraderRegistry.all().isEmpty()) {
            logger.info("record_migrator: done upgraded=0 failed=0 (no upgraders registered)");
            return;
        }

        int upgraded = 0;
        int failed   = 0;
        long lastId  = -1;

        while (true) {
            final List<Long> ids = fetchIdsBelow(target, lastId);
            if (ids.isEmpty())
                break;
            for (Long id : ids) {
                try {
                    upgradeOne(id, target);
                    upgraded++;
                } catch (Exception e) {
                    logger.error("record_migrator: row id={} upgrade failed: {}",
                            id, e.getMessage(), e);
                    failed++;
                }
                lastId = id;
            }
            // If the chunk wasn't full, we've reached the end.
            if (ids.size() < CHUNK_SIZE)
                break;
        }

        logger.info("record_migrator: done upgraded={} failed={}", upgraded, failed);
    }

    private List<Long> fetchIdsBelow(int target, long lastId) {
        final Connection db = MainServlet.openNewConnection();
        if (db == null)
            throw new IllegalStateException(
                    "record_migrator: openNewConnection returned null --- DB not configured?");
        boolean ok = false;
        try {
            final List<Long> ids = repo.findIdsBelowVersion(db, target, lastId, CHUNK_SIZE);
            ok = true;
            return ids;
        } catch (Exception e) {
            throw new IllegalStateException(
                    "record_migrator: id-scan failed: " + e.getMessage(), e);
        } finally {
            MainServlet.closeConnection(db, ok);
        }
    }

    /**
     * Upgrade one row's chain on its own connection/transaction.
     * Throws if any step in the chain fails --- the caller rolls back
     * by virtue of closeConnection(db, false), and the row stays at its
     * old record_version.
     */
    private void upgradeOne(long id, int target) throws Exception {
        final Connection db = MainServlet.openNewConnection();
        if (db == null)
            throw new IllegalStateException(
                    "record_migrator: openNewConnection returned null for id=" + id);
        boolean ok = false;
        try {
            final MemoryRow row = repo.findById(db, id);
            if (row == null) {
                // Row was hard-deleted between scan and now; treat as upgraded.
                ok = true;
                return;
            }

            int current = row.recordVersion;
            while (current < target) {
                final RecordUpgrader u = RecordUpgraderRegistry.forFromVersion(current);
                if (u == null)
                    throw new IllegalStateException(
                            "no RecordUpgrader registered for fromVersion=" + current);
                u.upgrade(db, row);
                current = u.toVersion();
            }
            // After the chain succeeds, bump the row's record_version atomically.
            repo.bumpVersion(db, id, target);
            ok = true;
        } finally {
            MainServlet.closeConnection(db, ok);
        }
    }
}
