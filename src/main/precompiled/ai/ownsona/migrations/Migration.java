package ai.ownsona.migrations;

import org.kissweb.database.Connection;

/**
 * A single, ordered, additive schema-or-data change applied automatically
 * at server startup by {@link DbMigrator}.
 *
 * <p>Contract:
 * <ul>
 *   <li>{@link #version()} is the target version this migration brings
 *       the database to.  Each migration's version must be exactly one
 *       greater than the previous one in the registry.  Version 1 is the
 *       baseline established by {@code sql/001_init.sql} and is NEVER
 *       represented as a Migration object --- the first registered
 *       migration is version 2.</li>
 *   <li>{@link #apply(Connection)} runs inside a transaction managed by
 *       {@code DbMigrator}.  Throw to abort; the transaction rolls back
 *       and {@code db_version} is not bumped, so the next startup
 *       retries from the same point.</li>
 *   <li>Migrations are <strong>permanent</strong>: once shipped, a
 *       migration class stays in the codebase forever.  Some old
 *       database somewhere may still need it.  Don't edit an
 *       already-shipped migration --- write a new one that fixes or
 *       supersedes it.</li>
 *   <li>Migrations stay <strong>additive</strong> (guardrail #2 in the
 *       rollout plan).  Add columns; add indexes; insert seed rows.
 *       Don't rewrite or delete existing data inside a migration ---
 *       that's a manual one-time operation under Procedure B.</li>
 * </ul>
 */
public interface Migration {

    /** Target version after this migration applies (= previous + 1). */
    int version();

    /** Short human-readable name; appears in logs. */
    String name();

    /**
     * Apply the migration.  Runs inside a transaction managed by
     * {@link DbMigrator}.  Throw on failure --- the transaction will
     * roll back and {@code db_version} stays at the prior value.
     */
    void apply(Connection db) throws Exception;
}
