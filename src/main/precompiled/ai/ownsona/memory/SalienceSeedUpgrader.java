package ai.ownsona.memory;

import org.kissweb.database.Connection;

/**
 * Record upgrader v1 &rarr; v2: seed the new {@code salience} column from
 * the row's existing {@code importance} (OwnSona learning-memory roadmap,
 * Tier 1).  Added alongside {@link ai.ownsona.migrations.Migration006AddSalience}.
 *
 * <p>Strictly additive and idempotent (invariant #3): it only fills
 * {@code salience} when it is still NULL (i.e. never seeded and never
 * reinforced).  A row whose salience has already been set --- by a prior
 * run of this upgrader or by a real {@code reinforce} / {@code confirm}
 * --- is left untouched, so re-running the walker can never clobber a
 * learned weight.  Original fields ({@code importance}, text, tags,
 * embedding) are never modified.
 */
public final class SalienceSeedUpgrader implements RecordUpgrader {

    @Override public int    fromVersion() { return 1; }
    @Override public int    toVersion()   { return 2; }
    @Override public String name()        { return "seed salience from importance"; }

    @Override
    public void upgrade(Connection db, MemoryRow row) throws Exception {
        // Only seed when salience is unset.  The guard makes this safe to
        // re-run and guarantees we never overwrite a value the user's
        // feedback has already moved.
        db.execute(
                "UPDATE memories SET salience = importance " +
                "WHERE id = ? AND salience IS NULL",
                row.id);
    }
}
