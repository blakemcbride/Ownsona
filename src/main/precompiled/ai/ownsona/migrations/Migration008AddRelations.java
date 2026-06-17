package ai.ownsona.migrations;

import org.kissweb.database.Connection;

/**
 * v8: add the relation/concept graph for Tier 4 phase 2 (graph-RAG /
 * multi-hop retrieval).
 *
 * <ul>
 *   <li>{@code memory_relations} -- one row per extracted
 *       {@code (subject, predicate, object)} triple, linked to the memory
 *       it came from. {@code ON DELETE CASCADE} so hard-deleting a memory
 *       removes its relations; soft-deleted memories' relations are simply
 *       filtered out at query time (the traversal joins to active rows).</li>
 *   <li>{@code memories.relations_extracted} -- a flag so the extraction
 *       job processes each memory at most once (cost bound). New rows
 *       default false (eligible); the job sets it true after attempting
 *       extraction, even when no triples were found.</li>
 * </ul>
 *
 * <p>Additive and idempotent (invariant #2): {@code CREATE TABLE IF NOT
 * EXISTS}, {@code CREATE INDEX IF NOT EXISTS}, {@code ADD COLUMN IF NOT
 * EXISTS}; nothing existing is rewritten. The migrator runs as the
 * {@code ownsona} role, which therefore owns {@code memory_relations} and
 * its sequence outright --- no extra GRANTs are needed.
 *
 * <p>The subject/object indexes are on {@code lower(...)} because the
 * multi-hop traversal matches entities case-insensitively.
 */
public final class Migration008AddRelations implements Migration {

    @Override public int    version() { return 8; }
    @Override public String name()    { return "add memory_relations graph + relations_extracted flag"; }

    @Override
    public void apply(Connection db) throws Exception {
        db.execute(
                "CREATE TABLE IF NOT EXISTS memory_relations ( " +
                "  id BIGSERIAL PRIMARY KEY, " +
                "  user_id TEXT NOT NULL DEFAULT 'default', " +
                "  subject TEXT NOT NULL, " +
                "  predicate TEXT NOT NULL, " +
                "  object TEXT NOT NULL, " +
                "  source_memory_id BIGINT REFERENCES memories(id) ON DELETE CASCADE, " +
                "  created_at TIMESTAMPTZ NOT NULL DEFAULT now() )");

        db.execute("CREATE INDEX IF NOT EXISTS memory_relations_subject_idx " +
                "ON memory_relations (lower(subject))");
        db.execute("CREATE INDEX IF NOT EXISTS memory_relations_object_idx " +
                "ON memory_relations (lower(object))");
        db.execute("CREATE INDEX IF NOT EXISTS memory_relations_user_idx " +
                "ON memory_relations (user_id)");
        db.execute("CREATE INDEX IF NOT EXISTS memory_relations_src_idx " +
                "ON memory_relations (source_memory_id)");

        db.execute(
                "ALTER TABLE memories " +
                "ADD COLUMN IF NOT EXISTS relations_extracted BOOLEAN NOT NULL DEFAULT false");

        // Partial index speeds the extraction job's "what still needs doing" scan.
        db.execute("CREATE INDEX IF NOT EXISTS memories_relations_unextracted_idx " +
                "ON memories(id) WHERE relations_extracted = false");
    }
}
