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
            "record_version, expires_at, last_confirmed_at, " +
            "forget_reason, replaced_by_id";

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
     * Vector similarity search restricted to <em>soft-deleted</em> rows
     * (tombstones).  Used by the dedup-on-write check to surface
     * previously-corrected facts so the caller doesn't silently re-add
     * a memory the user already forgot.  Same top-K, same ordering, same
     * SELECT_COLUMNS shape as {@link #findSimilar}; expired tombstones
     * are still included because the deletion is what matters, not the
     * (now-meaningless) expiration.
     */
    public List<MemoryRow> findSimilarTombstones(Connection db, String userId, float[] queryVec,
                                                 int limit) throws Exception {
        final String vecLiteral = VectorFormat.toLiteral(queryVec);
        final List<Record> rows = db.fetchAll(
                "SELECT " + SELECT_COLUMNS +
                ", 1 - (embedding <=> ?::vector) AS score " +
                "FROM memories " +
                "WHERE user_id = ? AND deleted_at IS NOT NULL " +
                "ORDER BY embedding <=> ?::vector LIMIT ?",
                vecLiteral, userId, vecLiteral, limit);
        final List<MemoryRow> out = new ArrayList<>(rows.size());
        for (Record r : rows) {
            final Double s = r.getDouble("score");
            out.add(toRow(r, s == null ? 0.0 : s));
        }
        return out;
    }

    /**
     * Find near-duplicate pairs among the user's active memories.
     *
     * <p>For each active row this queries its top-{@code topK} nearest
     * neighbors via pgvector's cosine operator (which uses the HNSW index
     * when available) and keeps the pairs whose cosine similarity is at
     * least {@code threshold}.  Pairs are canonicalized so the returned
     * id_a is always less than id_b --- the (a, b) / (b, a) duplicates
     * the lateral join produces collapse to a single row at this stage.
     *
     * <p>The query may return up to {@code N * topK} raw pairs (before
     * canonicalization) on a store with N active rows; for the typical
     * cleanup-tool use case ({@code threshold >= 0.90}, single-user store
     * of a few thousand rows or less) this is well under a second.
     *
     * @return list of {@code Object[]{Long idA, Long idB, Double similarity}},
     *         with {@code idA < idB}, sorted by similarity descending.
     *         Empty when no pairs meet the threshold.
     */
    public List<Object[]> findNearDuplicatePairs(Connection db, String userId,
                                                 double threshold, int topK) throws Exception {
        final List<Record> rows = db.fetchAll(
                "SELECT DISTINCT " +
                "  LEAST(a.id, n.id) AS id_a, GREATEST(a.id, n.id) AS id_b, " +
                "  1 - (a.embedding <=> n.embedding) AS similarity " +
                "FROM memories a " +
                "CROSS JOIN LATERAL ( " +
                "  SELECT b.id, b.embedding FROM memories b " +
                "  WHERE b.user_id = ? AND b.deleted_at IS NULL " +
                "    AND (b.expires_at IS NULL OR b.expires_at > now()) " +
                "    AND b.id <> a.id " +
                "  ORDER BY a.embedding <=> b.embedding " +
                "  LIMIT ? " +
                ") n " +
                "WHERE a.user_id = ? AND a.deleted_at IS NULL " +
                "  AND (a.expires_at IS NULL OR a.expires_at > now()) " +
                "  AND 1 - (a.embedding <=> n.embedding) >= ? " +
                "ORDER BY similarity DESC",
                userId, topK, userId, threshold);

        final List<Object[]> out = new ArrayList<>(rows.size());
        for (Record r : rows) {
            final Long   idA = r.getLong("id_a");
            final Long   idB = r.getLong("id_b");
            final Double sim = r.getDouble("similarity");
            if (idA == null || idB == null || sim == null)
                continue;
            out.add(new Object[]{ idA, idB, sim });
        }
        return out;
    }

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

    /**
     * Fetch up to {@code limit} memory ids that need re-embedding under the
     * active provider/model, paginating with {@code id > lastId}.  A row
     * needs re-embedding if its {@code embedding_provider} or
     * {@code embedding_model} doesn't match the active config, or its
     * {@code embedding} is NULL (which happens after a different-dim
     * column resize).  Soft-deleted rows ARE included --- tombstones still
     * participate in dedup-on-write, so their vectors must move into the
     * new model's space.
     *
     * <p>Used by {@code ReembedJob}.  Returns ids across all users.
     */
    public List<Long> findStaleEmbeddingIds(Connection db, String activeProvider, String activeModel,
                                            long lastId, int limit) throws Exception {
        final List<Record> rows = db.fetchAll(
                "SELECT id FROM memories " +
                "WHERE id > ? " +
                "  AND (embedding IS NULL " +
                "       OR embedding_provider IS DISTINCT FROM ? " +
                "       OR embedding_model    IS DISTINCT FROM ?) " +
                "ORDER BY id LIMIT ?",
                lastId, activeProvider, activeModel, limit);
        final List<Long> ids = new ArrayList<>(rows.size());
        for (Record r : rows)
            ids.add(r.getLong("id"));
        return ids;
    }

    /**
     * Convenience: fetch id+text for a list of ids, preserving id order.
     * Returns a list of {@code String[]{idAsString, text}} pairs because
     * Kiss's Record API doesn't expose a typed pair; callers parse the id
     * back to long.  Hard-deleted rows since the scan are simply absent.
     */
    public List<Object[]> fetchTextsByIds(Connection db, List<Long> ids) throws Exception {
        if (ids == null || ids.isEmpty())
            return new ArrayList<>();
        final StringBuilder sb = new StringBuilder(
                "SELECT id, text FROM memories WHERE id IN (");
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0)
                sb.append(',');
            sb.append('?');
        }
        sb.append(") ORDER BY id");
        final List<Record> rows = db.fetchAll(sb.toString(), ids.toArray());
        final List<Object[]> out = new ArrayList<>(rows.size());
        for (Record r : rows)
            out.add(new Object[] { r.getLong("id"), r.getString("text") });
        return out;
    }

    /**
     * Write a freshly computed embedding back to a row, stamping the new
     * provider/model.  Other columns are untouched --- in particular
     * {@code text}, {@code normalized_text}, and {@code deleted_at} are
     * left alone so a re-embed neither resurrects a tombstone nor alters
     * dedup state.  Used by {@code ReembedJob}.
     */
    public void setEmbedding(Connection db, long id, float[] embedding,
                             String embeddingProvider, String embeddingModel) throws Exception {
        db.execute(
                "UPDATE memories SET embedding = ?::vector, " +
                "embedding_provider = ?, embedding_model = ? " +
                "WHERE id = ?",
                VectorFormat.toLiteral(embedding),
                embeddingProvider,
                embeddingModel,
                id);
    }

    /**
     * Plain text substring search (case-insensitive).  Useful for diagnostics and direct lookup.
     */
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
     * Replace any subset of text / embedding / tags / importance /
     * freshness fields for an existing memory.  Returns true if a row was
     * updated.  Does not resurrect a soft-deleted row.
     *
     * <p>Each optional parameter follows "null = leave the column
     * unchanged" semantics.  When {@code text} is null the text /
     * normalized_text / embedding / embedding_provider / embedding_model
     * columns are all left alone --- callers doing a tags-only or
     * importance-only bulk update don't have to fetch and re-embed
     * unchanged text.  When {@code text} is non-null the caller MUST
     * also pass {@code normalizedText}, {@code embedding},
     * {@code embeddingProvider}, and {@code embeddingModel}; partial
     * combinations are rejected up front.
     *
     * <p>If every optional parameter is null this is a no-op: it
     * returns true if the row exists and is non-deleted, without
     * issuing any UPDATE.  This keeps batch callers from having to
     * special-case "empty item" rows.
     *
     * <p>Use the dedicated {@link #confirm} method to refresh
     * {@code last_confirmed_at} without rebuilding the embedding.
     */
    public boolean update(Connection db, long id, String text, String normalizedText,
                          float[] embedding, String[] tags, Double importance,
                          String embeddingProvider, String embeddingModel,
                          java.util.Date expiresAt, java.util.Date lastConfirmedAt) throws Exception {
        // Either every text-related field is supplied or none of them are;
        // partial combinations would leave the embedding out of sync with
        // the stored text.
        final boolean textProvided = text != null;
        if (textProvided && (normalizedText == null || embedding == null
                || embeddingProvider == null || embeddingModel == null))
            throw new IllegalArgumentException(
                    "update: text requires normalizedText, embedding, embeddingProvider, embeddingModel");

        final StringBuilder sb = new StringBuilder("UPDATE memories SET ");
        final List<Object> args = new ArrayList<>();
        boolean first = true;

        if (textProvided) {
            sb.append("text = ?, normalized_text = ?, embedding = ?::vector, " +
                      "embedding_provider = ?, embedding_model = ?");
            args.add(text);
            args.add(normalizedText);
            args.add(VectorFormat.toLiteral(embedding));
            args.add(embeddingProvider);
            args.add(embeddingModel);
            first = false;
        }
        if (tags != null) {
            if (!first) sb.append(", ");
            sb.append("tags = ?::text[]");
            args.add(VectorFormat.toPgArrayLiteral(tags));
            first = false;
        }
        if (importance != null) {
            if (!first) sb.append(", ");
            sb.append("importance = ?");
            args.add(importance);
            first = false;
        }
        if (expiresAt != null) {
            if (!first) sb.append(", ");
            sb.append("expires_at = ?");
            args.add(expiresAt);
            first = false;
        }
        if (lastConfirmedAt != null) {
            if (!first) sb.append(", ");
            sb.append("last_confirmed_at = ?");
            args.add(lastConfirmedAt);
            first = false;
        }

        if (first) {
            // No fields to change: skip the UPDATE entirely.  Just report
            // whether the row exists and is active so the caller sees the
            // same NOT_FOUND signal it would on a real update.
            final MemoryRow row = findById(db, id);
            return row != null && row.deletedAt == null;
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
     * Soft-delete: set {@code deleted_at = now()} if not already deleted,
     * and optionally record a {@code forget_reason} and/or
     * {@code replaced_by_id} describing why the row was forgotten or
     * corrected.  Null params are not written --- a plain forget with no
     * metadata stays compatible with the pre-Phase-5 behavior.
     *
     * <p>If the row is already soft-deleted, the tombstone metadata is
     * still updated (idempotent re-call lets a caller add a reason after
     * the fact).  Returns true when the row exists and ends in the
     * soft-deleted state.
     */
    public boolean softDelete(Connection db, long id, String forgetReason, Long replacedById) throws Exception {
        final StringBuilder sb = new StringBuilder(
                "UPDATE memories SET deleted_at = COALESCE(deleted_at, now())");
        final List<Object> args = new ArrayList<>();
        if (forgetReason != null) {
            sb.append(", forget_reason = ?");
            args.add(forgetReason);
        }
        if (replacedById != null) {
            sb.append(", replaced_by_id = ?");
            args.add(replacedById);
        }
        sb.append(" WHERE id = ?");
        args.add(id);
        db.execute(sb.toString(), args.toArray());
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

    /**
     * Count rows for a user with optional filters.  When
     * {@code includeDeleted} is false (the normal case) soft-deleted AND
     * expired rows are excluded.  When true, every row for the user is
     * counted regardless of state.
     *
     * <p>{@code tagFilter} (if non-empty) restricts to rows whose tags
     * overlap any of the listed tags.  {@code sourceProvider} (if
     * non-null) restricts to rows whose recorded source matches exactly.
     */
    public long count(Connection db, String userId, boolean includeDeleted,
                      String[] tagFilter, String sourceProvider) throws Exception {
        final boolean hasTags     = tagFilter != null && tagFilter.length > 0;
        final boolean hasProvider = sourceProvider != null && !sourceProvider.isEmpty();

        final StringBuilder sb = new StringBuilder("SELECT COUNT(*) AS n FROM memories WHERE user_id = ?");
        final List<Object> args = new ArrayList<>();
        args.add(userId);
        if (!includeDeleted)
            sb.append(" AND ").append(ACTIVE_AND_FRESH);
        if (hasTags) {
            sb.append(" AND tags && ?::text[]");
            args.add(VectorFormat.toPgArrayLiteral(tagFilter));
        }
        if (hasProvider) {
            sb.append(" AND source_provider = ?");
            args.add(sourceProvider);
        }
        final Record r = db.fetchOne(sb.toString(), args.toArray());
        if (r == null)
            return 0L;
        final Long n = r.getLong("n");
        return n == null ? 0L : n;
    }

    /**
     * Aggregate statistics for a user.  Counts active vs soft-deleted vs
     * expired separately; expired rows are mutually exclusive from active
     * (an expired-but-not-deleted row is counted as expired, not active).
     */
    public Stats stats(Connection db, String userId) throws Exception {
        final Stats s = new Stats();
        final Record r = db.fetchOne(
                "SELECT " +
                "  COUNT(*) AS total, " +
                "  COUNT(*) FILTER (WHERE deleted_at IS NULL AND (expires_at IS NULL OR expires_at > now())) AS active, " +
                "  COUNT(*) FILTER (WHERE deleted_at IS NOT NULL) AS soft_deleted, " +
                "  COUNT(*) FILTER (WHERE deleted_at IS NULL AND expires_at IS NOT NULL AND expires_at <= now()) AS expired, " +
                "  AVG(importance) FILTER (WHERE deleted_at IS NULL) AS avg_importance, " +
                "  MIN(created_at) FILTER (WHERE deleted_at IS NULL) AS oldest_created_at, " +
                "  MAX(created_at) FILTER (WHERE deleted_at IS NULL) AS newest_created_at " +
                "FROM memories WHERE user_id = ?",
                userId);
        if (r == null)
            return s;
        final Long total       = r.getLong("total");
        final Long active      = r.getLong("active");
        final Long deleted     = r.getLong("soft_deleted");
        final Long expired     = r.getLong("expired");
        s.total       = total == null ? 0L : total;
        s.active      = active == null ? 0L : active;
        s.softDeleted = deleted == null ? 0L : deleted;
        s.expired     = expired == null ? 0L : expired;
        s.avgImportance = r.getDouble("avg_importance");
        s.oldestCreatedAt = r.getDateTime("oldest_created_at");
        s.newestCreatedAt = r.getDateTime("newest_created_at");
        return s;
    }

    /**
     * Tag counts for a user, descending by count then by tag name for
     * stable ordering.  When {@code includeDeleted} is false (the normal
     * case) soft-deleted AND expired rows are excluded.
     */
    public List<TagCount> listTags(Connection db, String userId, boolean includeDeleted, int limit) throws Exception {
        final String where = includeDeleted
                ? "WHERE user_id = ?"
                : "WHERE user_id = ? AND " + ACTIVE_AND_FRESH;
        final List<Record> rows = db.fetchAll(
                "SELECT tag, COUNT(*) AS n " +
                "FROM memories, unnest(tags) AS tag " +
                where + " " +
                "GROUP BY tag " +
                "ORDER BY n DESC, tag ASC " +
                "LIMIT ?",
                userId, limit);
        final List<TagCount> out = new ArrayList<>(rows.size());
        for (Record r : rows) {
            final TagCount tc = new TagCount();
            tc.tag = r.getString("tag");
            final Long n = r.getLong("n");
            tc.count = n == null ? 0L : n;
            out.add(tc);
        }
        return out;
    }

    /**
     * Counts per source_provider for a user, descending by count.  NULL
     * source_provider values are bucketed as {@code "(none)"} so the
     * caller doesn't have to special-case missing data.  Soft-deleted
     * rows are excluded; expired rows are included because they were
     * still recorded under the same provider.
     */
    public List<ProviderCount> countsByProvider(Connection db, String userId) throws Exception {
        final List<Record> rows = db.fetchAll(
                "SELECT COALESCE(source_provider, '(none)') AS provider, COUNT(*) AS n " +
                "FROM memories " +
                "WHERE user_id = ? AND deleted_at IS NULL " +
                "GROUP BY provider " +
                "ORDER BY n DESC, provider ASC",
                userId);
        final List<ProviderCount> out = new ArrayList<>(rows.size());
        for (Record r : rows) {
            final ProviderCount pc = new ProviderCount();
            pc.provider = r.getString("provider");
            final Long n = r.getLong("n");
            pc.count = n == null ? 0L : n;
            out.add(pc);
        }
        return out;
    }

    /**
     * Dump every row for a user.  Unlike {@link #listRecent}, no LIMIT
     * is applied --- this is intended for {@code export_memories}, where
     * the caller wants the whole store.  When {@code includeDeleted} is
     * false, soft-deleted AND expired rows are excluded; when true, all
     * rows are returned regardless of state.
     */
    public List<MemoryRow> listAll(Connection db, String userId, boolean includeDeleted) throws Exception {
        final String sql = includeDeleted
                ? "SELECT " + SELECT_COLUMNS + " FROM memories WHERE user_id = ? ORDER BY created_at ASC, id ASC"
                : "SELECT " + SELECT_COLUMNS + " FROM memories WHERE user_id = ? AND " + ACTIVE_AND_FRESH +
                  " ORDER BY created_at ASC, id ASC";
        final List<Record> rows = db.fetchAll(sql, userId);
        final List<MemoryRow> out = new ArrayList<>(rows.size());
        for (Record r : rows)
            out.add(toRow(r, 0.0));
        return out;
    }

    /** Aggregate stats holder.  Public fields, no invariants. */
    public static final class Stats {
        public long   total;
        public long   active;
        public long   softDeleted;
        public long   expired;
        public Double avgImportance;   // null when no active rows
        public java.util.Date oldestCreatedAt;
        public java.util.Date newestCreatedAt;
    }

    /** Tag + count pair. */
    public static final class TagCount {
        public String tag;
        public long   count;
    }

    /** Provider + count pair. */
    public static final class ProviderCount {
        public String provider;
        public long   count;
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
        m.forgetReason         = r.getString("forget_reason");
        m.replacedById         = r.getLong("replaced_by_id");
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
