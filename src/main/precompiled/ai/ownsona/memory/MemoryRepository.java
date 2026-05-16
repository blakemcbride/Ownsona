package ai.ownsona.memory;

import ai.ownsona.VectorFormat;
import org.kissweb.database.Connection;
import org.kissweb.database.Record;
import org.kissweb.json.JSONArray;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Data-access layer for the {@code memories} table.
 *
 * <p>All SQL goes through Kiss's {@link org.kissweb.database.Connection}.
 * pgvector and {@code text[]} parameters are sent as text literals plus
 * {@code ?::vector} / {@code ?::text[]} casts in the SQL, because Kiss's
 * generic prepared-statement helpers don't know about Postgres custom
 * types.  The cast happens server-side and is just as safe as a typed
 * binding.
 *
 * <p>Methods take a {@link Connection} so the service layer can wrap
 * multi-statement flows (duplicate check + insert) on a single connection.
 */
public final class MemoryRepository {

    private static final String SELECT_COLUMNS =
            "id, text, importance, source_provider, source_conversation_id, " +
            "embedding_provider, embedding_model, " +
            "created_at, updated_at, deleted_at, " +
            "array_to_json(tags)::text AS tags_json, " +
            "metadata::text AS metadata_json, " +
            "record_version, expires_at, last_confirmed_at";

    /**
     * SQL fragment that excludes soft-deleted AND expired rows.  Used by
     * the read paths (recall, listRecent, textSearch) so a row with
     * {@code expires_at < now()} silently disappears from normal queries
     * but is still findable via id (findById) and via diagnostic listing.
     */
    private static final String ACTIVE_AND_FRESH =
            "deleted_at IS NULL AND (expires_at IS NULL OR expires_at > now())";

    /**
     * Insert a new memory and return the generated id.
     *
     * <p>We deliberately use {@code execute} + {@code currval} instead of
     * {@code INSERT ... RETURNING id} because Kiss's {@code fetchOne}
     * appends {@code LIMIT 1} to its query, which is a syntax error after
     * {@code RETURNING}.  {@code currval} is per-session and {@code db}
     * holds a single JDBC connection from the c3p0 pool, so the two calls
     * run in the same PostgreSQL session and the value is correct.
     */
    public long insert(Connection db, MemoryInsert m) throws Exception {
        // metadata column has a NOT NULL DEFAULT '{}'.  Passing null here would
        // be rejected by Postgres, so map null → '{}' explicitly.
        final String metadata = (m.metadataJson == null || m.metadataJson.isEmpty())
                ? "{}" : m.metadataJson;

        db.execute(
                "INSERT INTO memories " +
                " (user_id, text, normalized_text, embedding, tags, importance, " +
                "  source_provider, source_client, source_conversation_id, " +
                "  embedding_provider, embedding_model, metadata, record_version, " +
                "  expires_at, last_confirmed_at) " +
                "VALUES (?, ?, ?, ?::vector, ?::text[], ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?)",
                m.userId,
                m.text,
                m.normalizedText,
                VectorFormat.toLiteral(m.embedding),
                VectorFormat.toPgArrayLiteral(m.tags),
                m.importance,
                m.sourceProvider,
                m.sourceClient,
                m.sourceConversationId,
                m.embeddingProvider,
                m.embeddingModel,
                metadata,
                m.recordVersion,
                m.expiresAt,
                m.lastConfirmedAt);

        final Record r = db.fetchOne("SELECT currval('memories_id_seq') AS id");
        if (r == null)
            throw new SQLException("currval('memories_id_seq') returned no row");
        return r.getLong("id");
    }

    /**
     * Find an active memory id matching the same normalized text, used for duplicate detection.
     */
    public Long findActiveIdByNormalized(Connection db, String userId, String normalizedText) throws Exception {
        if (normalizedText == null)
            return null;
        final Record r = db.fetchOne(
                "SELECT id FROM memories WHERE user_id = ? AND normalized_text = ? AND deleted_at IS NULL",
                userId, normalizedText);
        return r == null ? null : r.getLong("id");
    }

    /**
     * Fetch a single memory by id.  Returns null if not found.  Includes deleted rows.
     */
    public MemoryRow findById(Connection db, long id) throws Exception {
        final Record r = db.fetchOne(
                "SELECT " + SELECT_COLUMNS + " FROM memories WHERE id = ?", id);
        return r == null ? null : toRow(r, 0.0);
    }

    /**
     * List memories for a user, most recent first.  When
     * {@code includeDeleted} is false (the normal case), expired rows
     * are also excluded --- "give me what's currently active."  When
     * {@code includeDeleted} is true (diagnostic listing), expired AND
     * soft-deleted rows are included.
     */
    public List<MemoryRow> listRecent(Connection db, String userId, int limit, int offset, boolean includeDeleted) throws Exception {
        final String sql;
        final List<Record> rows;
        if (includeDeleted) {
            sql = "SELECT " + SELECT_COLUMNS + " FROM memories " +
                  "WHERE user_id = ? " +
                  "ORDER BY created_at DESC LIMIT ? OFFSET ?";
            rows = db.fetchAll(sql, userId, limit, offset);
        } else {
            sql = "SELECT " + SELECT_COLUMNS + " FROM memories " +
                  "WHERE user_id = ? AND " + ACTIVE_AND_FRESH + " " +
                  "ORDER BY created_at DESC LIMIT ? OFFSET ?";
            rows = db.fetchAll(sql, userId, limit, offset);
        }

        final List<MemoryRow> out = new ArrayList<>(rows.size());
        for (Record r : rows)
            out.add(toRow(r, 0.0));
        return out;
    }

    /**
     * Vector similarity search.  If {@code tagFilter} is non-null and non-empty, only rows whose
     * {@code tags} overlap the filter are considered.
     */
    public List<MemoryRow> findSimilar(Connection db, String userId, float[] queryVec,
                                       int limit, String[] tagFilter) throws Exception {
        final String vecLiteral = VectorFormat.toLiteral(queryVec);
        final boolean hasTags = tagFilter != null && tagFilter.length > 0;

        final StringBuilder sb = new StringBuilder();
        sb.append("SELECT ").append(SELECT_COLUMNS)
          .append(", 1 - (embedding <=> ?::vector) AS score ")
          .append("FROM memories ")
          .append("WHERE user_id = ? AND ").append(ACTIVE_AND_FRESH);
        if (hasTags)
            sb.append(" AND tags && ?::text[]");
        sb.append(" ORDER BY embedding <=> ?::vector LIMIT ?");

        final List<Record> rows;
        if (hasTags) {
            rows = db.fetchAll(sb.toString(),
                    vecLiteral, userId, VectorFormat.toPgArrayLiteral(tagFilter), vecLiteral, limit);
        } else {
            rows = db.fetchAll(sb.toString(),
                    vecLiteral, userId, vecLiteral, limit);
        }

        final List<MemoryRow> out = new ArrayList<>(rows.size());
        for (Record r : rows) {
            final Double s = r.getDouble("score");
            out.add(toRow(r, s == null ? 0.0 : s));
        }
        return out;
    }

    /**
     * Plain text substring search (case-insensitive).  Useful for diagnostics and direct lookup.
     */
    /**
     * Fetch up to {@code limit} memory ids whose record_version is below
     * {@code targetVersion}, with id > {@code lastId} for pagination
     * (caller passes {@code -1} for the first page).  Used by
     * {@code RecordMigrator}.  Returns ids across all users and including
     * soft-deleted rows --- upgrading should bring every row to the
     * current data shape regardless.
     */
    public List<Long> findIdsBelowVersion(Connection db, int targetVersion,
                                          long lastId, int limit) throws Exception {
        final List<Record> rows = db.fetchAll(
                "SELECT id FROM memories " +
                "WHERE record_version < ? AND id > ? " +
                "ORDER BY id LIMIT ?",
                targetVersion, lastId, limit);
        final List<Long> ids = new ArrayList<>(rows.size());
        for (Record r : rows)
            ids.add(r.getLong("id"));
        return ids;
    }

    /**
     * Bump a single row's record_version.  Used by {@code RecordMigrator}
     * after a row's upgrader chain completes.  Returns true if a row
     * was updated.
     */
    public boolean bumpVersion(Connection db, long id, int newVersion) throws Exception {
        db.execute(
                "UPDATE memories SET record_version = ? WHERE id = ?",
                newVersion, id);
        final Record r = db.fetchOne(
                "SELECT record_version AS rv FROM memories WHERE id = ?", id);
        if (r == null)
            return false;
        final Integer rv = r.getInt("rv");
        return rv != null && rv == newVersion;
    }

    public List<MemoryRow> textSearch(Connection db, String userId, String pattern, int limit) throws Exception {
        final String like = "%" + escapeLike(pattern) + "%";
        final List<Record> rows = db.fetchAll(
                "SELECT " + SELECT_COLUMNS + " FROM memories " +
                "WHERE user_id = ? AND " + ACTIVE_AND_FRESH + " AND text ILIKE ? ESCAPE '\\' " +
                "ORDER BY created_at DESC LIMIT ?",
                userId, like, limit);
        final List<MemoryRow> out = new ArrayList<>(rows.size());
        for (Record r : rows)
            out.add(toRow(r, 0.0));
        return out;
    }

    /**
     * Replace text + embedding + optional tags/importance/freshness fields
     * for an existing memory.  Returns true if a row was updated.  Does
     * not resurrect a soft-deleted row.
     *
     * <p>Each optional parameter follows "null = leave the column
     * unchanged" semantics.  Use the dedicated {@link #confirm} method
     * to refresh {@code last_confirmed_at} without rebuilding the
     * embedding.
     */
    public boolean update(Connection db, long id, String text, String normalizedText,
                          float[] embedding, String[] tags, Double importance,
                          String embeddingProvider, String embeddingModel,
                          java.util.Date expiresAt, java.util.Date lastConfirmedAt) throws Exception {
        final StringBuilder sb = new StringBuilder();
        sb.append("UPDATE memories SET text = ?, normalized_text = ?, embedding = ?::vector, " +
                  "embedding_provider = ?, embedding_model = ?");
        final List<Object> args = new ArrayList<>();
        args.add(text);
        args.add(normalizedText);
        args.add(VectorFormat.toLiteral(embedding));
        args.add(embeddingProvider);
        args.add(embeddingModel);

        if (tags != null) {
            sb.append(", tags = ?::text[]");
            args.add(VectorFormat.toPgArrayLiteral(tags));
        }
        if (importance != null) {
            sb.append(", importance = ?");
            args.add(importance);
        }
        if (expiresAt != null) {
            sb.append(", expires_at = ?");
            args.add(expiresAt);
        }
        if (lastConfirmedAt != null) {
            sb.append(", last_confirmed_at = ?");
            args.add(lastConfirmedAt);
        }

        sb.append(" WHERE id = ? AND deleted_at IS NULL");
        args.add(id);

        // Connection.execute does not return a row count, so verify via a follow-up SELECT.
        // The WHERE filters soft-deleted rows out, so a deleted row is "found" by id but
        // unchanged --- check both presence and that it remains non-deleted.
        db.execute(sb.toString(), args.toArray());
        final MemoryRow row = findById(db, id);
        return row != null && row.deletedAt == null;
    }

    /**
     * Refresh {@code last_confirmed_at = now()} for an active (non-deleted)
     * row.  Cheap operation: no embedding rebuild, no other column change.
     * Returns true if a row was updated, false if the id doesn't exist
     * or the row is soft-deleted.
     */
    public boolean confirm(Connection db, long id) throws Exception {
        db.execute(
                "UPDATE memories SET last_confirmed_at = now() " +
                "WHERE id = ? AND deleted_at IS NULL",
                id);
        final MemoryRow row = findById(db, id);
        return row != null && row.deletedAt == null && row.lastConfirmedAt != null;
    }

    /**
     * Soft-delete: set {@code deleted_at = now()} if not already deleted.
     */
    public boolean softDelete(Connection db, long id) throws Exception {
        db.execute(
                "UPDATE memories SET deleted_at = now() WHERE id = ? AND deleted_at IS NULL",
                id);
        final Record r = db.fetchOne("SELECT deleted_at FROM memories WHERE id = ?", id);
        return r != null && r.getDateTime("deleted_at") != null;
    }

    /**
     * Hard delete: drop the row entirely.
     */
    public boolean hardDelete(Connection db, long id) throws Exception {
        final Record r = db.fetchOne("SELECT id FROM memories WHERE id = ?", id);
        if (r == null)
            return false;
        db.execute("DELETE FROM memories WHERE id = ?", id);
        return true;
    }

    private MemoryRow toRow(Record r, double score) throws SQLException {
        final MemoryRow m = new MemoryRow();
        m.id                   = r.getLong("id");
        m.text                 = r.getString("text");
        m.tags                 = parseTagsJson(r.getString("tags_json"));
        final Double imp       = r.getDouble("importance");
        m.importance           = imp == null ? 0.5 : imp;
        m.sourceProvider       = r.getString("source_provider");
        m.sourceConversationId = r.getString("source_conversation_id");
        m.embeddingProvider    = r.getString("embedding_provider");
        m.embeddingModel       = r.getString("embedding_model");
        m.createdAt            = r.getDateTime("created_at");
        m.updatedAt            = r.getDateTime("updated_at");
        m.deletedAt            = r.getDateTime("deleted_at");
        m.score                = score;
        final String md        = r.getString("metadata_json");
        m.metadataJson         = (md == null || md.isEmpty()) ? "{}" : md;
        final Integer rv       = r.getInt("record_version");
        m.recordVersion        = (rv == null) ? 1 : rv;
        m.expiresAt            = r.getDateTime("expires_at");
        m.lastConfirmedAt      = r.getDateTime("last_confirmed_at");
        return m;
    }

    private static String[] parseTagsJson(String json) {
        if (json == null || json.isEmpty() || "null".equals(json))
            return new String[0];
        final JSONArray arr = new JSONArray(json);
        final String[] out = new String[arr.length()];
        for (int i = 0; i < arr.length(); i++)
            out[i] = arr.getString(i);
        return out;
    }

    private static String escapeLike(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
