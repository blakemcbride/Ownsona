package ai.ownsona.migrations;

import org.kissweb.database.Connection;

/**
 * v3: add the freshness columns to {@code memories} so memories can carry
 * an optional expiration date and a "last confirmed" timestamp.
 *
 * <p>Both columns are nullable; existing rows stay null and behave
 * exactly as before.  {@code recall} and friends exclude rows where
 * {@code expires_at < now()}; new rows can be confirmed in place via the
 * {@code confirm} MCP tool without rebuilding the embedding.
 *
 * <p>The partial index on {@code expires_at IS NOT NULL} keeps the
 * index small --- most rows are durable and never set the column, so
 * indexing only the rows that do is much cheaper than a full-column
 * index.
 */
public final class Migration003AddFreshness implements Migration {

    @Override public int    version() { return 3; }
    @Override public String name()    { return "add expires_at, last_confirmed_at"; }

    @Override
    public void apply(Connection db) throws Exception {
        db.execute(
                "ALTER TABLE memories " +
                "ADD COLUMN IF NOT EXISTS expires_at        TIMESTAMPTZ, " +
                "ADD COLUMN IF NOT EXISTS last_confirmed_at TIMESTAMPTZ");

        db.execute(
                "CREATE INDEX IF NOT EXISTS memories_expires_at_idx " +
                "ON memories(expires_at) " +
                "WHERE expires_at IS NOT NULL");
    }
}
