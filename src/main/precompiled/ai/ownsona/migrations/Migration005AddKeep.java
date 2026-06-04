package ai.ownsona.migrations;

import org.kissweb.database.Connection;
import org.kissweb.database.Record;

/**
 * v5: add the {@code keep} flag to {@code memories}.  A single-character
 * column with three legal values:
 *
 * <ul>
 *   <li>{@code U} -- Unspecified (the default for every row)</li>
 *   <li>{@code Y} -- protected: the memory cannot be changed or deleted
 *       by any client; only the {@code ownsona} CLI may toggle the flag
 *       back off</li>
 *   <li>{@code N} -- explicitly not protected</li>
 * </ul>
 *
 * <p>Existing rows get {@code U} via the column default, so the CHECK
 * constraint can never fail on the backfill.  Additive and idempotent
 * (invariant #2): {@code ADD COLUMN IF NOT EXISTS} plus a guarded
 * constraint add make this a no-op on a database that already has the
 * column (e.g. a fresh install whose baseline already created it, or a
 * retried migration).
 */
public final class Migration005AddKeep implements Migration {

    @Override public int    version() { return 5; }
    @Override public String name()    { return "add keep flag"; }

    @Override
    public void apply(Connection db) throws Exception {
        db.execute(
                "ALTER TABLE memories " +
                "ADD COLUMN IF NOT EXISTS keep CHAR(1) NOT NULL DEFAULT 'U'");

        // Postgres has no "ADD CONSTRAINT IF NOT EXISTS", so guard the add
        // by looking the constraint up in the catalog first.  This keeps
        // the migration idempotent across retries.
        final Record c = db.fetchOne(
                "SELECT 1 AS x FROM pg_constraint WHERE conname = ?",
                "memories_keep_check");
        if (c == null)
            db.execute(
                    "ALTER TABLE memories " +
                    "ADD CONSTRAINT memories_keep_check CHECK (keep IN ('Y','N','U'))");
    }
}
