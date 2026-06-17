package ai.ownsona.memory;

import ai.ownsona.VectorFormat;
import org.kissweb.database.Connection;
import org.kissweb.database.Record;

import java.util.ArrayList;
import java.util.List;

/**
 * Data-access layer for the {@code memory_relations} graph table (Tier 4
 * phase 2).  Kept separate from {@link MemoryRepository} so the relation
 * graph is a clean, optional add-on.
 *
 * <p>Entity matching is case-insensitive (the subject/object indexes are
 * on {@code lower(...)}).  The {@code text[]} frontier is sent as a pgvector-
 * style array literal plus an {@code ?::text[]} cast, the same approach
 * {@link MemoryRepository} uses for tags.
 */
public final class RelationRepository {

    /** Insert one extracted triple linked to its source memory. */
    public void insert(Connection db, String userId, String subject, String predicate,
                       String object, long sourceMemoryId) throws Exception {
        db.execute(
                "INSERT INTO memory_relations (user_id, subject, predicate, object, source_memory_id) " +
                "VALUES (?, ?, ?, ?, ?)",
                userId, subject, predicate, object, sourceMemoryId);
    }

    /** Delete every relation extracted from a given memory (used before re-extraction). */
    public void deleteByMemory(Connection db, long sourceMemoryId) throws Exception {
        db.execute("DELETE FROM memory_relations WHERE source_memory_id = ?", sourceMemoryId);
    }

    /**
     * Find all relations incident on any of the given (already-lowercased)
     * entities, restricted to the user and to <em>active</em> source
     * memories (a soft-deleted memory's edges drop out of traversal).  The
     * current source text is joined in.  Capped at {@code limit} rows.
     */
    public List<MemoryRelation> findRelationsTouching(Connection db, String userId,
                                                      List<String> entitiesLower, int limit) throws Exception {
        if (entitiesLower == null || entitiesLower.isEmpty())
            return new ArrayList<>();
        final String arr = VectorFormat.toPgArrayLiteral(entitiesLower.toArray(new String[0]));
        final List<Record> rows = db.fetchAll(
                "SELECT r.id, r.subject, r.predicate, r.object, r.source_memory_id, " +
                "       m.text AS source_text " +
                "FROM memory_relations r " +
                "JOIN memories m ON m.id = r.source_memory_id " +
                "WHERE r.user_id = ? AND m.deleted_at IS NULL " +
                "  AND (lower(r.subject) = ANY(?::text[]) OR lower(r.object) = ANY(?::text[])) " +
                "ORDER BY r.id " +
                "LIMIT ?",
                userId, arr, arr, limit);
        final List<MemoryRelation> out = new ArrayList<>(rows.size());
        for (Record r : rows) {
            final MemoryRelation mr = new MemoryRelation();
            mr.id             = r.getLong("id");
            mr.subject        = r.getString("subject");
            mr.predicate      = r.getString("predicate");
            mr.object         = r.getString("object");
            final Long src    = r.getLong("source_memory_id");
            mr.sourceMemoryId = src == null ? 0L : src;
            mr.sourceText     = r.getString("source_text");
            out.add(mr);
        }
        return out;
    }
}
