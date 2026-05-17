package ai.ownsona.migrations;

import org.kissweb.database.Connection;

/**
 * v4: turn the existing soft-delete state into a richer "tombstone" by
 * adding two optional columns: {@code forget_reason} (why the user
 * forgot or corrected this fact) and {@code replaced_by_id} (the new
 * memory that supersedes this one, if any).
 *
 * <p>Both nullable; existing soft-deleted rows stay null and behave
 * exactly as before.  The {@code REFERENCES memories(id)} FK on
 * {@code replaced_by_id} catches obvious mistakes (pointing at a
 * nonexistent memory) at insert time.
 *
 * <p>Tombstones still don't show up in normal recall (they're
 * {@code deleted_at IS NOT NULL}); they're consulted only by the
 * dedup-on-write check from Phase 4 so a previously-corrected fact
 * doesn't silently re-enter the store.
 */
public final class Migration004AddTombstones implements Migration {

    @Override public int    version() { return 4; }
    @Override public String name()    { return "add forget_reason, replaced_by_id"; }

    @Override
    public void apply(Connection db) throws Exception {
        db.execute(
                "ALTER TABLE memories " +
                "ADD COLUMN IF NOT EXISTS forget_reason  TEXT, " +
                "ADD COLUMN IF NOT EXISTS replaced_by_id BIGINT REFERENCES memories(id)");
    }
}
