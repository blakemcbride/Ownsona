package ai.ownsona.migrations;

import org.kissweb.database.Connection;

/**
 * v6: add the learned-salience columns to {@code memories} so the store
 * can rank by a reinforcement-driven weight instead of cosine similarity
 * alone (OwnSona learning-memory roadmap, Tier 1).
 *
 * <ul>
 *   <li>{@code salience}    -- learned weight; rises on use / reinforcement
 *       / confirm.  Nullable on purpose: existing rows stay NULL until the
 *       per-row {@link ai.ownsona.memory.SalienceSeedUpgrader} seeds them
 *       from {@code importance}.  The read paths COALESCE NULL to
 *       importance, so behavior is unchanged until a row is seeded.</li>
 *   <li>{@code use_count}   -- how many times the row has been reinforced /
 *       confirmed.</li>
 *   <li>{@code reward_sum}  -- running sum of the rewards applied, for
 *       diagnostics.</li>
 *   <li>{@code last_used_at} -- timestamp of the last reinforcement /
 *       confirm; feeds the recency tiebreaker in recall ranking.</li>
 * </ul>
 *
 * <p>Additive and idempotent (invariant #2): every column is added with
 * {@code ADD COLUMN IF NOT EXISTS}.  No existing value is rewritten ---
 * seeding {@code salience} from {@code importance} is done per-row by the
 * record upgrader, not here, so this migration can never clobber a row.
 *
 * <p><strong>No time-based decay or pruning.</strong>  A memory is never
 * removed or down-weighted for being old; salience only changes on
 * explicit reinforcement / confirm.  Recency enters ranking solely as a
 * tiebreaker (see {@code MemoryRepository.findRanked}).
 */
public final class Migration006AddSalience implements Migration {

    @Override public int    version() { return 6; }
    @Override public String name()    { return "add salience, use_count, reward_sum, last_used_at"; }

    @Override
    public void apply(Connection db) throws Exception {
        db.execute(
                "ALTER TABLE memories " +
                "ADD COLUMN IF NOT EXISTS salience     DOUBLE PRECISION, " +
                "ADD COLUMN IF NOT EXISTS use_count    INTEGER          NOT NULL DEFAULT 0, " +
                "ADD COLUMN IF NOT EXISTS reward_sum   DOUBLE PRECISION NOT NULL DEFAULT 0.0, " +
                "ADD COLUMN IF NOT EXISTS last_used_at TIMESTAMPTZ");
    }
}
