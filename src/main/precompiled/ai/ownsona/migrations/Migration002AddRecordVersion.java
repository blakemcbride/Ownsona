package ai.ownsona.migrations;

import org.kissweb.database.Connection;

/**
 * v2: add the {@code record_version} column to {@code memories} so each
 * row can carry its own data-shape version, independent of the
 * database-wide schema version tracked by {@link DbMigrator}.
 *
 * <p>Existing rows get version 1 via the column default.  New rows
 * inserted by the running code explicitly set
 * {@code record_version = RecordUpgraderRegistry.CURRENT_RECORD_VERSION}.
 *
 * <p>The partial index speeds up the per-record walker's
 * "find rows below target" scan.  The {@code 100} high-water mark is
 * arbitrary; queries always use {@code record_version < N} where N is
 * the current target.
 */
public final class Migration002AddRecordVersion implements Migration {

    @Override public int    version() { return 2; }
    @Override public String name()    { return "add record_version column"; }

    @Override
    public void apply(Connection db) throws Exception {
        db.execute(
                "ALTER TABLE memories " +
                "ADD COLUMN IF NOT EXISTS record_version INT NOT NULL DEFAULT 1");

        db.execute(
                "CREATE INDEX IF NOT EXISTS memories_record_version_idx " +
                "ON memories(record_version) " +
                "WHERE record_version < 100");
    }
}
