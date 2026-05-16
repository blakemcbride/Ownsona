package ai.ownsona.memory;

import org.kissweb.database.Connection;

/**
 * Per-row, strictly additive data upgrader applied at startup by
 * {@link RecordMigrator} to bring memories rows from one
 * {@code record_version} to the next.
 *
 * <p>Contract (rollout plan guardrail #10):
 * <ul>
 *   <li><strong>Strictly additive.</strong> An upgrader may fill in a
 *       new column or metadata key from existing data, set a default,
 *       or otherwise enrich a row.  It MUST NOT overwrite, transform,
 *       or delete existing values.  Original memory text, tags,
 *       embeddings, and timestamps are immutable from an upgrader's
 *       point of view.</li>
 *   <li><strong>Idempotent.</strong> Running the same upgrader on the
 *       same row a second time must produce the same outcome.  This
 *       protects against half-finished startup passes and lets the
 *       walker retry safely.</li>
 *   <li><strong>Per-row isolation.</strong> Failures in
 *       {@link #upgrade(Connection, MemoryRow)} roll back just that
 *       row's transaction; other rows continue.  Startup is never
 *       blocked by per-row failures.</li>
 *   <li><strong>Permanent.</strong> Once shipped, an upgrader stays in
 *       the codebase forever.  Old rows may still need it.  Don't edit
 *       an already-shipped upgrader --- write a new one for the next
 *       version transition.</li>
 *   <li><strong>Destructive rewrites are NOT upgraders.</strong>
 *       Anything that replaces existing data (re-embedding rows under
 *       a new model, normalizing existing tags through a synonym map,
 *       etc.) is a manual one-time data migration, not an upgrader.</li>
 * </ul>
 *
 * <p>An upgrader takes a row at {@link #fromVersion()} and brings it
 * to {@link #toVersion()} (typically {@code fromVersion() + 1}; chained
 * upgraders are how rows advance multiple versions at once).
 */
public interface RecordUpgrader {

    /** Row's record_version that this upgrader knows how to upgrade FROM. */
    int fromVersion();

    /**
     * Version the upgrader brings the row TO.  Typically
     * {@code fromVersion() + 1}.
     */
    int toVersion();

    /** Short human-readable name; appears in logs. */
    String name();

    /**
     * Apply the upgrade to a single row.  The row's stored fields are
     * available via {@code row}; modify the database directly through
     * {@code db}.  Runs inside a transaction managed by
     * {@link RecordMigrator}; throw to abort just this row's upgrade.
     * Do NOT bump {@code record_version} here --- the migrator does
     * that after the upgrader chain completes.
     */
    void upgrade(Connection db, MemoryRow row) throws Exception;
}
