package ai.ownsona.migrations;

import org.kissweb.database.Connection;

/**
 * v7: add the learned-context columns to {@code memories} for the Tier 4
 * contextual retrieval policy.
 *
 * <ul>
 *   <li>{@code context_vector} -- a reward-weighted centroid of the query
 *       embeddings for which this memory was reinforced as helpful. Recall
 *       boosts a memory when the current query is close to this centroid,
 *       so the store learns <em>which memory helps for which kind of
 *       query</em>, beyond the global {@code salience} scalar. Nullable:
 *       a row has no centroid until it is reinforced with a query context,
 *       and the read path treats NULL as "no contextual signal" (behaves
 *       exactly as before).</li>
 *   <li>{@code context_count} -- how many context-bearing reinforcements
 *       have shaped the centroid (diagnostics).</li>
 * </ul>
 *
 * <p>Additive and idempotent (invariant #2): {@code ADD COLUMN IF NOT
 * EXISTS}, nothing rewritten. The vector dimension matches the
 * {@code embedding} column (1536); a future embedding-dimension change must
 * resize this column alongside {@code embedding}.
 *
 * <p>No index: {@code context_vector} is used only to compute a per-row
 * scalar during re-ranking of the already-fetched candidate set, never for
 * approximate-nearest-neighbor search, so it needs no HNSW index.
 */
public final class Migration007AddContextVector implements Migration {

    @Override public int    version() { return 7; }
    @Override public String name()    { return "add context_vector, context_count"; }

    @Override
    public void apply(Connection db) throws Exception {
        db.execute(
                "ALTER TABLE memories " +
                "ADD COLUMN IF NOT EXISTS context_vector vector(1536), " +
                "ADD COLUMN IF NOT EXISTS context_count  integer NOT NULL DEFAULT 0");
    }
}
