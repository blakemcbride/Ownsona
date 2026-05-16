package ai.ownsona.migrations;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.kissweb.database.Connection;
import org.kissweb.database.Record;
import org.kissweb.restServer.MainServlet;

/**
 * Startup-time auto-migrator.  Brings the database from whatever version
 * it is at up to {@link MigrationRegistry#CURRENT_DB_VERSION} by applying
 * registered {@link Migration}s in order.
 *
 * <p>Called once from {@code MCPServer.<clinit>}.  Each migration runs
 * on its own connection (its own transaction); failure rolls that
 * migration back and throws --- {@code db_version} is not bumped, so the
 * next startup will retry from the same point.
 *
 * <p>Refuses to start if the database is at a version newer than the
 * running code expects (rollback scenario, or someone deployed an older
 * WAR on top of a newer schema).
 *
 * <p>See "Auto-migration framework" in {@code OwnSona-rollout-plan.md}
 * for the design rationale.
 */
public final class DbMigrator {

    private static final Logger logger = LogManager.getLogger(DbMigrator.class);

    private DbMigrator() {
    }

    /**
     * Run the migrator.  Throws {@link IllegalStateException} on any
     * failure; the caller (MCPServer's static initializer) is expected
     * to let it propagate so the servlet refuses to load and Tomcat
     * surfaces the failure in catalina.out.
     */
    public static void runOnStartup() {
        // 1. Fail fast on programmer error before any DB operation.
        MigrationRegistry.validate();

        final int target = MigrationRegistry.CURRENT_DB_VERSION;

        // 2. Ensure db_version table exists and has at least version 1.
        int current = bootstrap();

        // 3. Refuse if DB is ahead of code.
        if (current > target) {
            throw new IllegalStateException(
                    "Database is at version " + current + " but code expects " + target +
                    " --- refusing to start.  Either deploy a build with " +
                    "CURRENT_DB_VERSION >= " + current + " or restore the database " +
                    "to a snapshot at version " + target + ".");
        }

        // 4. No migrations needed.
        if (current == target) {
            logger.info("migrator: db_version at {}, target {}, nothing to apply",
                    current, target);
            return;
        }

        // 5. Apply each registered migration whose version > current.
        for (Migration m : MigrationRegistry.all()) {
            if (m.version() <= current)
                continue;
            applyOne(m);
            current = m.version();
        }
        logger.info("migrator: db_version now {} (matches CURRENT_DB_VERSION)", current);
    }

    /**
     * Create the db_version table if missing and seed it with version 1
     * if empty.  Returns the current MAX(version).
     *
     * <p>Wraps DB failures with a hint pointing at the most likely cause
     * (missing CREATE privilege on schema public --- a one-time setup
     * step the application role doesn't have by default on Postgres 15+).
     */
    private static int bootstrap() {
        final Connection db = MainServlet.openNewConnection();
        if (db == null)
            throw new IllegalStateException(
                    "MainServlet.openNewConnection returned null --- is the " +
                    "database configured in application.ini?");
        boolean ok = false;
        try {
            db.execute(
                    "CREATE TABLE IF NOT EXISTS db_version (" +
                    "  version    INT PRIMARY KEY, " +
                    "  applied_at TIMESTAMPTZ NOT NULL DEFAULT now(), " +
                    "  note       TEXT)");
            // ON CONFLICT makes the seed idempotent across restarts and
            // safe against the unlikely concurrent-bootstrap race.
            db.execute(
                    "INSERT INTO db_version (version, note) VALUES (1, ?) " +
                    "ON CONFLICT (version) DO NOTHING",
                    "baseline (001_init.sql)");
            final Record r = db.fetchOne(
                    "SELECT COALESCE(MAX(version), 0) AS v FROM db_version");
            final Integer v = (r == null) ? null : r.getInt("v");
            final int current = (v == null) ? 0 : v;
            ok = true;
            if (current == 1)
                logger.info("migrator: db_version baseline established (version=1)");
            else
                logger.info("migrator: db_version table present, current version={}", current);
            return current;
        } catch (Exception e) {
            throw new IllegalStateException(
                    "migrator bootstrap failed: " + e.getMessage() +
                    " --- if this is the first deploy with the auto-migrator, the " +
                    "application role may need 'GRANT CREATE ON SCHEMA public TO " +
                    "<role>' as the postgres superuser before this code can run.",
                    e);
        } finally {
            MainServlet.closeConnection(db, ok);
        }
    }

    /**
     * Apply one migration on a fresh connection.  Successful apply +
     * db_version bump commit together; any failure rolls both back.
     */
    private static void applyOne(Migration m) {
        final Connection db = MainServlet.openNewConnection();
        if (db == null)
            throw new IllegalStateException(
                    "openNewConnection returned null during migration v=" + m.version());
        boolean ok = false;
        final long t0 = System.currentTimeMillis();
        try {
            m.apply(db);
            db.execute(
                    "INSERT INTO db_version (version, note) VALUES (?, ?)",
                    m.version(), m.name());
            ok = true;
            logger.info("migrator: applied v={} name=\"{}\" ms={}",
                    m.version(), m.name(), System.currentTimeMillis() - t0);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "migration v=" + m.version() + " name=\"" + m.name() +
                    "\" failed: " + e.getMessage(), e);
        } finally {
            MainServlet.closeConnection(db, ok);
        }
    }
}
