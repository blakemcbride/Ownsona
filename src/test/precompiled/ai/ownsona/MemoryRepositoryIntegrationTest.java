package ai.ownsona;

import ai.ownsona.embeddings.MockEmbeddingProvider;
import ai.ownsona.memory.MemoryInsert;
import ai.ownsona.memory.MemoryRepository;
import ai.ownsona.memory.MemoryRow;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.kissweb.database.Connection;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises every {@link MemoryRepository} method against a real PostgreSQL
 * database with pgvector and pg_trgm.  Skipped unless
 * {@code OWNSONA_TEST_DATABASE_URL} is set in the environment.
 *
 * <p>The test database must already have the schema applied (run
 * {@code sql/001_init.sql} against it).  Each test starts from an empty
 * {@code memories} table, so the test database must be one this suite is
 * allowed to wipe.
 */
@EnabledIfEnvironmentVariable(named = "OWNSONA_TEST_DATABASE_URL", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MemoryRepositoryIntegrationTest {

    private static final String USER_ID = "test-user";

    private final MemoryRepository repo = new MemoryRepository();
    private final MockEmbeddingProvider embedder = new MockEmbeddingProvider(1536);

    private java.sql.Connection rawJdbc;
    private Connection db;

    @BeforeAll
    void connect() throws Exception {
        Class.forName("org.postgresql.Driver");
        final String url = System.getenv("OWNSONA_TEST_DATABASE_URL");
        rawJdbc = openJdbc(url);
        db = new Connection(rawJdbc);
    }

    @AfterAll
    void disconnect() throws Exception {
        if (db != null)
            db.close();
    }

    @BeforeEach
    void wipe() throws Exception {
        // Plain TRUNCATE (without RESTART IDENTITY) so the role only needs
        // TRUNCATE on the table, not ownership of the sequence.
        db.execute("TRUNCATE memories");
    }

    @Test
    void insertAndFindById() throws Exception {
        final long id = insert("My son Colby lives in Los Angeles.", new String[]{"family"});
        final MemoryRow row = repo.findById(db, id);
        assertNotNull(row);
        assertEquals(id, row.id);
        assertEquals("My son Colby lives in Los Angeles.", row.text);
        assertArrayEquals(new String[]{"family"}, row.tags);
        assertEquals("mock-sha256", row.embeddingModel);
        assertNull(row.deletedAt);
    }

    @Test
    void duplicateDetectionViaNormalizedKey() throws Exception {
        insert("Hello world", new String[]{});
        final Long active = repo.findActiveIdByNormalized(db, USER_ID, "hello world");
        assertNotNull(active);

        final Long missing = repo.findActiveIdByNormalized(db, USER_ID, "different normalized text");
        assertNull(missing);
    }

    @Test
    void recallReturnsMostSimilarFirst() throws Exception {
        final long id1 = insert("My son Colby works for Dropbox.", new String[]{"family", "work"});
        final long id2 = insert("Coffee shops near me.",            new String[]{"food"});

        final float[] queryVec = embedder.embed("Where does my son work?");
        final List<MemoryRow> hits = repo.findSimilar(db, USER_ID, queryVec, 5, null);
        assertEquals(2, hits.size());
        // Mock embedder produces deterministic but uncorrelated vectors.  We
        // don't assert which is closer --- only that scores are populated and
        // results are ordered by descending similarity.
        assertTrue(hits.get(0).score >= hits.get(1).score);
        for (MemoryRow m : hits)
            assertTrue(m.id == id1 || m.id == id2);
    }

    @Test
    void tagFilterRestrictsResults() throws Exception {
        final long workId = insert("My son works for Dropbox.", new String[]{"work"});
        final long foodId = insert("My favorite coffee.",       new String[]{"food"});

        final float[] q = embedder.embed("anything");
        final List<MemoryRow> workOnly = repo.findSimilar(db, USER_ID, q, 5, new String[]{"work"});
        assertEquals(1, workOnly.size());
        assertEquals(workId, workOnly.get(0).id);

        final List<MemoryRow> foodOrWork = repo.findSimilar(db, USER_ID, q, 5, new String[]{"food", "work"});
        assertEquals(2, foodOrWork.size());

        final List<MemoryRow> none = repo.findSimilar(db, USER_ID, q, 5, new String[]{"nope"});
        assertEquals(0, none.size());

        // Make a use of foodId so the compiler doesn't warn it's unused.
        assertTrue(foodId > 0);
    }

    @Test
    void textSearchCaseInsensitive() throws Exception {
        insert("My son Colby lives in Los Angeles.", new String[]{"family"});
        insert("I love coffee in the morning.",     new String[]{"preferences"});

        final List<MemoryRow> hits = repo.textSearch(db, USER_ID, "COLBY", 10);
        assertEquals(1, hits.size());
        assertTrue(hits.get(0).text.contains("Colby"));

        final List<MemoryRow> none = repo.textSearch(db, USER_ID, "no_such_substring", 10);
        assertEquals(0, none.size());
    }

    @Test
    void textSearchEscapesLikeMetacharacters() throws Exception {
        insert("Discount: 10% off",       new String[]{});
        insert("Plain ten percent text",  new String[]{});

        final List<MemoryRow> hits = repo.textSearch(db, USER_ID, "10%", 10);
        // The literal "10%" should match only the first row, not all rows containing "10".
        assertEquals(1, hits.size());
        assertTrue(hits.get(0).text.contains("10%"));
    }

    @Test
    void updateChangesTextEmbeddingAndRegenerates() throws Exception {
        final long id = insert("My son Colby works for Dropbox.", new String[]{"family", "work"});
        final MemoryRow before = repo.findById(db, id);

        final String newText = "My son Colby now works for Anthropic.";
        final float[] newVec = embedder.embed(newText);

        final boolean ok = repo.update(db, id, newText,
                TextNormalizer.normalize(newText), newVec,
                new String[]{"family", "work", "anthropic"}, 0.8,
                "mock", "mock-sha256",
                null, null);
        assertTrue(ok);

        final MemoryRow after = repo.findById(db, id);
        assertEquals(newText, after.text);
        assertArrayEquals(new String[]{"family", "work", "anthropic"}, after.tags);
        assertEquals(0.8, after.importance, 1e-9);
        assertTrue(after.updatedAt.getTime() >= before.updatedAt.getTime());
    }

    @Test
    void softDeleteHidesFromRecallAndListButFindByIdStillFinds() throws Exception {
        final long id = insert("Forgettable fact.", new String[]{});
        assertTrue(repo.softDelete(db, id, null, null));

        // findById still returns it (with deletedAt set)
        final MemoryRow row = repo.findById(db, id);
        assertNotNull(row);
        assertNotNull(row.deletedAt);

        // listRecent (default = exclude deleted) does not see it
        assertEquals(0, repo.listRecent(db, USER_ID, 10, 0, false).size());
        // listRecent with includeDeleted does
        assertEquals(1, repo.listRecent(db, USER_ID, 10, 0, true).size());

        // similarity recall does not see it
        final float[] q = embedder.embed("anything");
        assertEquals(0, repo.findSimilar(db, USER_ID, q, 10, null).size());

        // text search does not see it
        assertEquals(0, repo.textSearch(db, USER_ID, "Forgettable", 10).size());
    }

    @Test
    void softDeleteIsIdempotentAndUpdateRefusesDeletedRow() throws Exception {
        final long id = insert("Will be deleted.", new String[]{});
        assertTrue(repo.softDelete(db, id, null, null));
        // Second call: row is already deleted; method returns whether it ended up deleted, which is still true.
        assertTrue(repo.softDelete(db, id, null, null));

        // update on a soft-deleted row should not "resurrect" it.
        final boolean updated = repo.update(db, id, "new text", "new text",
                embedder.embed("new text"), null, null, "mock", "mock-sha256",
                null, null);
        assertFalse(updated, "update should refuse a soft-deleted row");
    }

    @Test
    void hardDeleteRemovesRow() throws Exception {
        final long id = insert("Erase me.", new String[]{});
        assertTrue(repo.hardDelete(db, id));
        assertNull(repo.findById(db, id));
        // hard-deleting a missing row returns false
        assertFalse(repo.hardDelete(db, id));
    }

    @Test
    void listRecentSortsDescByCreatedAtAndPaginates() throws Exception {
        final long oldId = insert("First", new String[]{});
        Thread.sleep(15);  // ensure created_at differs
        final long midId = insert("Second", new String[]{});
        Thread.sleep(15);
        final long newId = insert("Third", new String[]{});

        final List<MemoryRow> page1 = repo.listRecent(db, USER_ID, 2, 0, false);
        assertEquals(2, page1.size());
        assertEquals(newId, page1.get(0).id);
        assertEquals(midId, page1.get(1).id);

        final List<MemoryRow> page2 = repo.listRecent(db, USER_ID, 2, 2, false);
        assertEquals(1, page2.size());
        assertEquals(oldId, page2.get(0).id);
    }

    @Test
    void metadataAndSessionIdRoundTrip() throws Exception {
        final MemoryInsert m = new MemoryInsert();
        m.userId               = USER_ID;
        m.text                 = "Provenance round-trip fact.";
        m.normalizedText       = TextNormalizer.normalize(m.text);
        m.embedding            = embedder.embed(m.text);
        m.tags                 = new String[]{"meta"};
        m.importance           = 0.5;
        m.sourceConversationId = "session-xyz";
        m.metadataJson         = "{\"capture_mode\":\"explicit\"}";
        m.embeddingProvider    = "mock";
        m.embeddingModel       = embedder.modelName();
        final long id = repo.insert(db, m);

        final MemoryRow row = repo.findById(db, id);
        assertNotNull(row);
        assertEquals("session-xyz", row.sourceConversationId);
        assertNotNull(row.metadataJson);
        assertTrue(row.metadataJson.contains("\"capture_mode\""),
                "expected capture_mode key in metadata, got: " + row.metadataJson);
        assertTrue(row.metadataJson.contains("\"explicit\""),
                "expected explicit value in metadata, got: " + row.metadataJson);
    }

    @Test
    void metadataDefaultsToEmptyObjectWhenNotSet() throws Exception {
        final long id = insert("Plain old fact.", new String[]{});
        final MemoryRow row = repo.findById(db, id);
        assertNotNull(row);
        assertNull(row.sourceConversationId);
        assertEquals("{}", row.metadataJson);
    }

    @Test
    void recordVersionRoundTripExplicit() throws Exception {
        // Insert with an explicit non-default recordVersion to prove the
        // column is wired through INSERT and SELECT, not just defaulted.
        final MemoryInsert m = new MemoryInsert();
        m.userId            = USER_ID;
        m.text              = "Versioned fact.";
        m.normalizedText    = TextNormalizer.normalize(m.text);
        m.embedding         = embedder.embed(m.text);
        m.tags              = new String[]{};
        m.importance        = 0.5;
        m.embeddingProvider = "mock";
        m.embeddingModel    = embedder.modelName();
        m.recordVersion     = 5;
        final long id = repo.insert(db, m);

        final MemoryRow row = repo.findById(db, id);
        assertNotNull(row);
        assertEquals(5, row.recordVersion);
    }

    @Test
    void recordVersionDefaultsToOneWhenMemoryInsertLeavesItUnset() throws Exception {
        // The insert() helper below uses MemoryInsert's default recordVersion = 1.
        final long id = insert("Default-version fact.", new String[]{});
        final MemoryRow row = repo.findById(db, id);
        assertNotNull(row);
        assertEquals(1, row.recordVersion);
    }

    @Test
    void findIdsBelowVersionReturnsRowsBelowTarget() throws Exception {
        // Two at v=1, one at v=2.  Target=2 should return only the v=1 ids.
        final long lowA = insert("Low one.", new String[]{});
        final long lowB = insert("Low two.", new String[]{});
        final MemoryInsert high = new MemoryInsert();
        high.userId            = USER_ID;
        high.text              = "Already current.";
        high.normalizedText    = TextNormalizer.normalize(high.text);
        high.embedding         = embedder.embed(high.text);
        high.tags              = new String[]{};
        high.importance        = 0.5;
        high.embeddingProvider = "mock";
        high.embeddingModel    = embedder.modelName();
        high.recordVersion     = 2;
        repo.insert(db, high);

        final java.util.List<Long> ids = repo.findIdsBelowVersion(db, 2, -1, 10);
        assertEquals(2, ids.size());
        assertTrue(ids.contains(lowA));
        assertTrue(ids.contains(lowB));
    }

    @Test
    void bumpVersionUpdatesAndConfirms() throws Exception {
        final long id = insert("Bumpable.", new String[]{});
        assertTrue(repo.bumpVersion(db, id, 3));
        final MemoryRow row = repo.findById(db, id);
        assertNotNull(row);
        assertEquals(3, row.recordVersion);
    }

    @Test
    void expiresAtAndLastConfirmedAtRoundTrip() throws Exception {
        final java.util.Date future = new java.util.Date(System.currentTimeMillis()
                + 30L * 24L * 60L * 60L * 1000L);   // 30 days out
        final java.util.Date past = new java.util.Date(System.currentTimeMillis()
                - 24L * 60L * 60L * 1000L);          // yesterday

        final MemoryInsert m = new MemoryInsert();
        m.userId            = USER_ID;
        m.text              = "Expires-and-confirmed fact.";
        m.normalizedText    = TextNormalizer.normalize(m.text);
        m.embedding         = embedder.embed(m.text);
        m.tags              = new String[]{};
        m.importance        = 0.5;
        m.embeddingProvider = "mock";
        m.embeddingModel    = embedder.modelName();
        m.expiresAt         = future;
        m.lastConfirmedAt   = past;
        final long id = repo.insert(db, m);

        final MemoryRow row = repo.findById(db, id);
        assertNotNull(row);
        assertNotNull(row.expiresAt);
        assertEquals(future.getTime(), row.expiresAt.getTime());
        assertNotNull(row.lastConfirmedAt);
        assertEquals(past.getTime(), row.lastConfirmedAt.getTime());
    }

    @Test
    void expiredRowsExcludedFromRecallAndListAndTextSearch() throws Exception {
        final java.util.Date past = new java.util.Date(System.currentTimeMillis()
                - 24L * 60L * 60L * 1000L);   // yesterday

        // Insert one fresh, one already-expired.
        final long freshId = insert("Fresh fact about pickles.", new String[]{});
        final MemoryInsert expired = new MemoryInsert();
        expired.userId            = USER_ID;
        expired.text              = "Expired fact about apricots.";
        expired.normalizedText    = TextNormalizer.normalize(expired.text);
        expired.embedding         = embedder.embed(expired.text);
        expired.tags              = new String[]{};
        expired.importance        = 0.5;
        expired.embeddingProvider = "mock";
        expired.embeddingModel    = embedder.modelName();
        expired.expiresAt         = past;
        repo.insert(db, expired);

        // recall sees only the fresh one.
        final float[] q = embedder.embed("any query");
        final List<MemoryRow> hits = repo.findSimilar(db, USER_ID, q, 10, null);
        assertEquals(1, hits.size());
        assertEquals(freshId, hits.get(0).id);

        // listRecent (default, exclude deleted/expired) sees only the fresh one.
        assertEquals(1, repo.listRecent(db, USER_ID, 10, 0, false).size());
        // listRecent with includeDeleted=true sees both.
        assertEquals(2, repo.listRecent(db, USER_ID, 10, 0, true).size());

        // textSearch on "apricots" sees nothing (expired excluded).
        assertEquals(0, repo.textSearch(db, USER_ID, "apricots", 10).size());
        // textSearch on "pickles" still finds the fresh row.
        assertEquals(1, repo.textSearch(db, USER_ID, "pickles", 10).size());
    }

    @Test
    void confirmRefreshesLastConfirmedAt() throws Exception {
        final long id = insert("Confirmable fact.", new String[]{});
        // Initially null --- never confirmed.
        final MemoryRow before = repo.findById(db, id);
        assertNull(before.lastConfirmedAt);

        assertTrue(repo.confirm(db, id));

        final MemoryRow after = repo.findById(db, id);
        assertNotNull(after.lastConfirmedAt);
        // The timestamp was set by SQL now(); just verify it's recent.
        final long now = System.currentTimeMillis();
        assertTrue(Math.abs(after.lastConfirmedAt.getTime() - now) < 60L * 1000L,
                "last_confirmed_at should be within ~1 min of now");
    }

    @Test
    void confirmReturnsFalseForUnknownIdAndDeletedRow() throws Exception {
        assertFalse(repo.confirm(db, 99_999_999L));

        final long id = insert("Will be deleted.", new String[]{});
        assertTrue(repo.softDelete(db, id, null, null));
        assertFalse(repo.confirm(db, id), "confirm should refuse a soft-deleted row");
    }

    @Test
    void softDeletePersistsForgetReasonAndReplacedById() throws Exception {
        final long oldId = insert("My dog's name is Coco.", new String[]{"family"});
        final long newId = insert("My dog's name is Mochi.", new String[]{"family"});

        assertTrue(repo.softDelete(db, oldId, "misremembered name", newId));

        final MemoryRow row = repo.findById(db, oldId);
        assertNotNull(row);
        assertNotNull(row.deletedAt);
        assertEquals("misremembered name", row.forgetReason);
        assertNotNull(row.replacedById);
        assertEquals(newId, row.replacedById.longValue());
    }

    @Test
    void softDeleteWithoutReasonLeavesTombstoneFieldsNull() throws Exception {
        final long id = insert("Plain forget.", new String[]{});
        assertTrue(repo.softDelete(db, id, null, null));

        final MemoryRow row = repo.findById(db, id);
        assertNotNull(row);
        assertNotNull(row.deletedAt);
        assertNull(row.forgetReason);
        assertNull(row.replacedById);
    }

    @Test
    void findSimilarTombstonesReturnsOnlySoftDeletedRows() throws Exception {
        final long activeId  = insert("Active fact about ferrets.", new String[]{});
        final long deletedId = insert("Deleted fact about ferrets.", new String[]{});
        assertTrue(repo.softDelete(db, deletedId, "corrected", null));

        final float[] q = embedder.embed("anything");
        final List<MemoryRow> hits = repo.findSimilarTombstones(db, USER_ID, q, 10);
        assertEquals(1, hits.size());
        assertEquals(deletedId, hits.get(0).id);
        assertNotNull(hits.get(0).deletedAt);
        assertEquals("corrected", hits.get(0).forgetReason);

        // findSimilar (active path) sees only the active row.
        final List<MemoryRow> activeHits = repo.findSimilar(db, USER_ID, q, 10, null);
        assertEquals(1, activeHits.size());
        assertEquals(activeId, activeHits.get(0).id);
    }

    // -------------------------------------------------------------------------------
    // ReembedJob support methods (findStaleEmbeddingIds / fetchTextsByIds /
    // setEmbedding).  Pure-SQL coverage --- the walker itself is exercised
    // in production.

    @Test
    void findStaleEmbeddingIds_returnsRowsWithMismatchedProviderOrModel() throws Exception {
        final long matching     = insert("matching",     new String[]{}); // provider=mock, model=embedder.modelName()
        final long otherProvider = insertWithProvider("other-provider", "wrong-provider", "mock-sha256");
        final long otherModel    = insertWithProvider("other-model",    "mock",            "old-model");
        final long bothOther     = insertWithProvider("both-other",     "wrong-provider", "old-model");

        final List<Long> stale = repo.findStaleEmbeddingIds(db, "mock", "mock-sha256", -1, 100);

        // The "matching" row should not appear; everything else does.
        assertFalse(stale.contains(matching));
        assertTrue(stale.contains(otherProvider));
        assertTrue(stale.contains(otherModel));
        assertTrue(stale.contains(bothOther));
    }

    @Test
    void findStaleEmbeddingIds_paginatesViaLastId() throws Exception {
        // Insert three rows that all need re-embedding.
        final long a = insertWithProvider("a", "wrong", "x");
        final long b = insertWithProvider("b", "wrong", "x");
        final long c = insertWithProvider("c", "wrong", "x");

        final List<Long> page1 = repo.findStaleEmbeddingIds(db, "mock", "mock-sha256", -1, 2);
        assertEquals(2, page1.size());
        assertEquals(a, (long) page1.get(0));
        assertEquals(b, (long) page1.get(1));

        final List<Long> page2 = repo.findStaleEmbeddingIds(db, "mock", "mock-sha256", page1.get(1), 2);
        assertEquals(1, page2.size());
        assertEquals(c, (long) page2.get(0));

        final List<Long> page3 = repo.findStaleEmbeddingIds(db, "mock", "mock-sha256", page2.get(0), 2);
        assertEquals(0, page3.size());
    }

    @Test
    void findStaleEmbeddingIds_includesSoftDeletedRows() throws Exception {
        // Tombstones must move with the rest of the store --- they
        // participate in dedup-on-write via findSimilarTombstones.
        final long tombstoned = insertWithProvider("tombstoned", "wrong", "x");
        repo.softDelete(db, tombstoned, "old", null);

        final List<Long> stale = repo.findStaleEmbeddingIds(db, "mock", "mock-sha256", -1, 100);
        assertTrue(stale.contains(tombstoned));
    }

    @Test
    void fetchTextsByIds_returnsIdsAndTextsInOrder() throws Exception {
        final long a = insert("alpha", new String[]{});
        final long b = insert("bravo", new String[]{});

        final List<Object[]> pairs = repo.fetchTextsByIds(db, List.of(a, b));
        assertEquals(2, pairs.size());
        assertEquals(a, pairs.get(0)[0]);
        assertEquals("alpha", pairs.get(0)[1]);
        assertEquals(b, pairs.get(1)[0]);
        assertEquals("bravo", pairs.get(1)[1]);
    }

    @Test
    void fetchTextsByIds_handlesHardDeletedIdsAsAbsent() throws Exception {
        final long a = insert("alpha", new String[]{});
        final long b = insert("bravo", new String[]{});
        repo.hardDelete(db, a);

        final List<Object[]> pairs = repo.fetchTextsByIds(db, List.of(a, b));
        assertEquals(1, pairs.size());
        assertEquals(b, pairs.get(0)[0]);
    }

    @Test
    void setEmbedding_stampsNewProviderAndModelLeavingTextUntouched() throws Exception {
        final long id = insertWithProvider("hello", "old-provider", "old-model");
        final float[] newVec = embedder.embed("hello, again");  // any vector of the right dim

        repo.setEmbedding(db, id, newVec, "new-provider", "new-model");

        final MemoryRow after = repo.findById(db, id);
        assertEquals("hello", after.text);  // text untouched
        assertEquals("new-provider", after.embeddingProvider);
        assertEquals("new-model", after.embeddingModel);

        // And the row no longer shows up as stale under the new (provider, model).
        final List<Long> stale = repo.findStaleEmbeddingIds(db, "new-provider", "new-model", -1, 100);
        assertFalse(stale.contains(id));
    }

    @Test
    void setEmbedding_leavesSoftDeletedRowsSoftDeleted() throws Exception {
        final long id = insertWithProvider("hello", "old", "x");
        repo.softDelete(db, id, "obsolete", null);

        final float[] newVec = embedder.embed("hello");
        repo.setEmbedding(db, id, newVec, "new", "y");

        final MemoryRow after = repo.findById(db, id);
        assertNotNull(after.deletedAt);  // tombstone preserved
        assertEquals("new", after.embeddingProvider);
    }

    // -------------------------------------------------------------------------------
    // Count / stats / tags / export support methods.

    @Test
    void count_excludesSoftDeletedAndExpiredByDefault() throws Exception {
        insert("active one", new String[]{"a"});
        insert("active two", new String[]{"a", "b"});
        final long del = insert("will be deleted", new String[]{"a"});
        repo.softDelete(db, del, null, null);

        // Insert an already-expired row.
        final MemoryInsert exp = new MemoryInsert();
        exp.userId            = USER_ID;
        exp.text              = "stale one";
        exp.normalizedText    = TextNormalizer.normalize(exp.text);
        exp.embedding         = embedder.embed(exp.text);
        exp.tags              = new String[]{"a"};
        exp.importance        = 0.5;
        exp.embeddingProvider = "mock";
        exp.embeddingModel    = embedder.modelName();
        exp.expiresAt         = new java.util.Date(System.currentTimeMillis() - 60_000L);
        repo.insert(db, exp);

        // Default: active only (2).  include_deleted=true: everything (4).
        assertEquals(2L, repo.count(db, USER_ID, false, null, null));
        assertEquals(4L, repo.count(db, USER_ID, true,  null, null));
    }

    @Test
    void count_filtersByTagOverlapAndSourceProvider() throws Exception {
        insert("family fact",  new String[]{"family"});
        insert("work fact",    new String[]{"work"});
        insert("both fact",    new String[]{"family", "work"});

        // tag-overlap filter
        assertEquals(2L, repo.count(db, USER_ID, false, new String[]{"family"}, null));
        assertEquals(2L, repo.count(db, USER_ID, false, new String[]{"work"},   null));
        assertEquals(3L, repo.count(db, USER_ID, false, new String[]{"family", "work"}, null));
        assertEquals(0L, repo.count(db, USER_ID, false, new String[]{"nope"},   null));

        // source_provider filter
        final long withProv = insertWithSource("sourced fact", "claude");
        assertEquals(1L, repo.count(db, USER_ID, false, null, "claude"));
        assertEquals(0L, repo.count(db, USER_ID, false, null, "no-such-provider"));
        assertTrue(withProv > 0);
    }

    @Test
    void stats_breaksDownActiveSoftDeletedExpired() throws Exception {
        // 2 active, 1 soft-deleted, 1 expired (not deleted).
        insert("a1", new String[]{"x"});
        insert("a2", new String[]{"x", "y"});
        final long delId = insert("to-delete", new String[]{"x"});
        repo.softDelete(db, delId, null, null);

        final MemoryInsert exp = new MemoryInsert();
        exp.userId            = USER_ID;
        exp.text              = "expired-now";
        exp.normalizedText    = TextNormalizer.normalize(exp.text);
        exp.embedding         = embedder.embed(exp.text);
        exp.tags              = new String[]{"x"};
        exp.importance        = 0.7;
        exp.embeddingProvider = "mock";
        exp.embeddingModel    = embedder.modelName();
        exp.expiresAt         = new java.util.Date(System.currentTimeMillis() - 60_000L);
        repo.insert(db, exp);

        final MemoryRepository.Stats s = repo.stats(db, USER_ID);
        assertEquals(4L, s.total);
        assertEquals(2L, s.active);
        assertEquals(1L, s.softDeleted);
        assertEquals(1L, s.expired);
        // avg_importance is averaged over rows with deleted_at IS NULL ---
        // so the 2 active rows (0.5, 0.5) and the 1 expired-but-undeleted row
        // (0.7), excluding the soft-deleted row.  (0.5 + 0.5 + 0.7) / 3.
        assertNotNull(s.avgImportance);
        assertEquals((0.5 + 0.5 + 0.7) / 3.0, s.avgImportance, 1e-9);
        assertNotNull(s.oldestCreatedAt);
        assertNotNull(s.newestCreatedAt);
        assertTrue(s.newestCreatedAt.getTime() >= s.oldestCreatedAt.getTime());
    }

    @Test
    void stats_emptyStoreReportsZeros() throws Exception {
        final MemoryRepository.Stats s = repo.stats(db, USER_ID);
        assertEquals(0L, s.total);
        assertEquals(0L, s.active);
        assertEquals(0L, s.softDeleted);
        assertEquals(0L, s.expired);
        assertNull(s.avgImportance);
        assertNull(s.oldestCreatedAt);
        assertNull(s.newestCreatedAt);
    }

    @Test
    void listTags_returnsCountsDescendingActiveOnly() throws Exception {
        insert("one",   new String[]{"family"});
        insert("two",   new String[]{"family", "work"});
        insert("three", new String[]{"work"});
        final long del = insert("four", new String[]{"deleted-only"});
        repo.softDelete(db, del, null, null);

        // Default: exclude deleted.  family=2, work=2, deleted-only absent.
        final List<MemoryRepository.TagCount> active = repo.listTags(db, USER_ID, false, 50);
        assertEquals(2, active.size());
        // Counts are equal so order is alphabetical: family, work.
        assertEquals("family", active.get(0).tag);
        assertEquals(2L,       active.get(0).count);
        assertEquals("work",   active.get(1).tag);
        assertEquals(2L,       active.get(1).count);

        // include_deleted=true also surfaces the tombstoned tag.
        final List<MemoryRepository.TagCount> all = repo.listTags(db, USER_ID, true, 50);
        assertEquals(3, all.size());
        boolean sawDeletedOnly = false;
        for (MemoryRepository.TagCount tc : all)
            if ("deleted-only".equals(tc.tag))
                sawDeletedOnly = true;
        assertTrue(sawDeletedOnly);
    }

    @Test
    void listTags_respectsLimit() throws Exception {
        insert("a", new String[]{"alpha"});
        insert("b", new String[]{"beta"});
        insert("c", new String[]{"gamma"});
        final List<MemoryRepository.TagCount> top2 = repo.listTags(db, USER_ID, false, 2);
        assertEquals(2, top2.size());
    }

    @Test
    void countsByProvider_groupsAndBucketsNulls() throws Exception {
        insertWithSource("c1", "claude");
        insertWithSource("c2", "claude");
        insertWithSource("g1", "gemini");
        insert("anon", new String[]{});   // no source_provider

        final List<MemoryRepository.ProviderCount> by = repo.countsByProvider(db, USER_ID);
        assertEquals(3, by.size());
        // claude=2 is the top by count and must come first.  The order
        // between the two ties (gemini=1 vs (none)=1) is locale-dependent
        // --- some PostgreSQL collations (en_US.UTF-8) treat parentheses as
        // ignorable and sort '(none)' AFTER 'gemini'; others sort by raw
        // codepoint and put '(none)' first.  Assert membership for the
        // tied entries instead of locking in either order.
        assertEquals("claude", by.get(0).provider);
        assertEquals(2L,       by.get(0).count);
        final java.util.Set<String> tied = new java.util.HashSet<>();
        tied.add(by.get(1).provider);
        tied.add(by.get(2).provider);
        assertEquals(1L, by.get(1).count);
        assertEquals(1L, by.get(2).count);
        assertEquals(new java.util.HashSet<>(java.util.Arrays.asList("gemini", "(none)")), tied);
    }

    @Test
    void countsByProvider_excludesSoftDeleted() throws Exception {
        final long id = insertWithSource("temp", "claude");
        repo.softDelete(db, id, null, null);
        final List<MemoryRepository.ProviderCount> by = repo.countsByProvider(db, USER_ID);
        assertTrue(by.isEmpty(), "soft-deleted rows should be excluded; got " + by.size());
    }

    @Test
    void listAll_returnsEverythingInOrder() throws Exception {
        final long a = insert("first",  new String[]{});
        Thread.sleep(15);
        final long b = insert("second", new String[]{});
        Thread.sleep(15);
        final long c = insert("third",  new String[]{"t"});
        repo.softDelete(db, b, "nope", null);

        // include_deleted=false: a, c only, oldest first.
        final List<MemoryRow> active = repo.listAll(db, USER_ID, false);
        assertEquals(2, active.size());
        assertEquals(a, active.get(0).id);
        assertEquals(c, active.get(1).id);

        // include_deleted=true: a, b (soft-deleted), c.
        final List<MemoryRow> all = repo.listAll(db, USER_ID, true);
        assertEquals(3, all.size());
        assertEquals(a, all.get(0).id);
        assertEquals(b, all.get(1).id);
        assertNotNull(all.get(1).deletedAt);
        assertEquals(c, all.get(2).id);
    }

    // ====================================================================================
    // update with null text (partial-update path added for update_memory_batch)
    // ====================================================================================

    @Test
    void updateTagsOnlyLeavesTextAndEmbeddingUntouched() throws Exception {
        final long id = insert("Original.", new String[]{"old"});
        final MemoryRow before = repo.findById(db, id);

        final boolean ok = repo.update(db, id, null, null, null,
                new String[]{"new"}, null, null, null, null, null);
        assertTrue(ok);

        final MemoryRow after = repo.findById(db, id);
        assertEquals("Original.", after.text);
        assertArrayEquals(new String[]{"new"}, after.tags);
        assertEquals(before.embeddingProvider, after.embeddingProvider);
        assertEquals(before.embeddingModel,    after.embeddingModel);
    }

    @Test
    void updateImportanceOnlyChangesOnlyImportance() throws Exception {
        final long id = insert("Quiet update.", new String[]{"tag-a"});
        final MemoryRow before = repo.findById(db, id);

        final boolean ok = repo.update(db, id, null, null, null,
                null, 0.99, null, null, null, null);
        assertTrue(ok);

        final MemoryRow after = repo.findById(db, id);
        assertEquals("Quiet update.", after.text);
        assertArrayEquals(new String[]{"tag-a"}, after.tags);
        assertEquals(0.99, after.importance, 1e-9);
        assertEquals(before.embeddingProvider, after.embeddingProvider);
    }

    @Test
    void updateLastConfirmedOnlySetsThatColumn() throws Exception {
        final long id = insert("Refresh me.", new String[]{});
        final java.util.Date stamp = new java.util.Date();

        final boolean ok = repo.update(db, id, null, null, null,
                null, null, null, null, null, stamp);
        assertTrue(ok);

        final MemoryRow after = repo.findById(db, id);
        assertNotNull(after.lastConfirmedAt);
        assertEquals("Refresh me.", after.text);
    }

    @Test
    void updateAllNullParamsIsNoOpButReportsTrueWhenActive() throws Exception {
        final long id = insert("Untouched.", new String[]{"keep"});
        final MemoryRow before = repo.findById(db, id);

        final boolean ok = repo.update(db, id, null, null, null,
                null, null, null, null, null, null);
        assertTrue(ok, "no-op update on an active row reports true");

        final MemoryRow after = repo.findById(db, id);
        // No UPDATE was issued so the BEFORE UPDATE trigger didn't fire;
        // updated_at must stay exactly as it was.
        assertEquals(before.updatedAt.getTime(), after.updatedAt.getTime(),
                "no-op update must not bump updated_at");
        assertEquals(before.text, after.text);
        assertArrayEquals(before.tags, after.tags);
    }

    @Test
    void updateAllNullParamsOnDeletedRowReturnsFalse() throws Exception {
        final long id = insert("Will be deleted.", new String[]{});
        repo.softDelete(db, id, null, null);

        final boolean ok = repo.update(db, id, null, null, null,
                null, null, null, null, null, null);
        assertFalse(ok, "no-op update on a soft-deleted row must report false");
    }

    @Test
    void updateAllNullParamsOnMissingRowReturnsFalse() throws Exception {
        final boolean ok = repo.update(db, 999_999L, null, null, null,
                null, null, null, null, null, null);
        assertFalse(ok);
    }

    @Test
    void updateWithTextButMissingEmbeddingParamsThrows() throws Exception {
        final long id = insert("With text.", new String[]{});
        assertThrows(IllegalArgumentException.class,
                () -> repo.update(db, id, "new text", "new text", null,
                        null, null, "mock", "mock-sha256", null, null));
    }

    @Test
    void updateTagsOnlyOnDeletedRowDoesNotResurrect() throws Exception {
        final long id = insert("Tags-only resurrection probe.", new String[]{"old"});
        repo.softDelete(db, id, null, null);

        final boolean ok = repo.update(db, id, null, null, null,
                new String[]{"new"}, null, null, null, null, null);
        assertFalse(ok, "tags-only update on a soft-deleted row must report false");

        // The row's tags should not have changed; the WHERE deleted_at IS NULL
        // guard filters it out of the actual UPDATE.
        final MemoryRow row = repo.findById(db, id);
        assertArrayEquals(new String[]{"old"}, row.tags);
    }

    // ====================================================================================
    // findNearDuplicatePairs
    // ====================================================================================

    @Test
    void findNearDuplicatesEmptyOnEmptyDb() throws Exception {
        final List<Object[]> pairs = repo.findNearDuplicatePairs(db, USER_ID, 0.9, 10);
        assertEquals(0, pairs.size());
    }

    @Test
    void findNearDuplicatesReturnsOnePairForIdenticalEmbeddings() throws Exception {
        final float[] vec = embedder.embed("seed");
        final long a = insertWithEmbedding("First row.",  vec);
        final long b = insertWithEmbedding("Second row.", vec);

        final List<Object[]> pairs = repo.findNearDuplicatePairs(db, USER_ID, 0.9, 10);
        assertEquals(1, pairs.size());
        final long idA = ((Long) pairs.get(0)[0]).longValue();
        final long idB = ((Long) pairs.get(0)[1]).longValue();
        assertEquals(Math.min(a, b), idA);
        assertEquals(Math.max(a, b), idB);
        assertTrue(idA < idB, "pairs are canonicalized");
        assertEquals(1.0, ((Double) pairs.get(0)[2]).doubleValue(), 1e-3);
    }

    @Test
    void findNearDuplicatesAllSimilarYieldsAllPairs() throws Exception {
        final float[] vec = embedder.embed("shared");
        insertWithEmbedding("First.",  vec);
        insertWithEmbedding("Second.", vec);
        insertWithEmbedding("Third.",  vec);

        final List<Object[]> pairs = repo.findNearDuplicatePairs(db, USER_ID, 0.9, 10);
        // 3 rows -> 3 unordered pairs (1-2, 1-3, 2-3).
        assertEquals(3, pairs.size());
        for (Object[] p : pairs)
            assertEquals(1.0, ((Double) p[2]).doubleValue(), 1e-3);
    }

    @Test
    void findNearDuplicatesUncorrelatedEmbeddingsBelowThresholdReturnNothing() throws Exception {
        // The mock embedder makes different strings produce uncorrelated vectors
        // (cosine ~0); at threshold 0.9 they must not pair.
        insert("totally different alpha", new String[]{});
        insert("totally different bravo", new String[]{});

        final List<Object[]> pairs = repo.findNearDuplicatePairs(db, USER_ID, 0.9, 10);
        assertEquals(0, pairs.size());
    }

    @Test
    void findNearDuplicatesExcludesSoftDeletedRows() throws Exception {
        final float[] vec = embedder.embed("seed");
        final long a = insertWithEmbedding("Alpha.", vec);
        insertWithEmbedding("Bravo.", vec);
        repo.softDelete(db, a, null, null);

        final List<Object[]> pairs = repo.findNearDuplicatePairs(db, USER_ID, 0.9, 10);
        assertEquals(0, pairs.size(), "soft-deleted rows must not appear in either side of the pair");
    }

    @Test
    void findNearDuplicatesExcludesExpiredRows() throws Exception {
        final float[] vec = embedder.embed("seed");
        final long a = insertWithEmbedding("Alpha.", vec);
        insertWithEmbedding("Bravo.", vec);
        db.execute("UPDATE memories SET expires_at = now() - interval '1 day' WHERE id = ?", a);

        final List<Object[]> pairs = repo.findNearDuplicatePairs(db, USER_ID, 0.9, 10);
        assertEquals(0, pairs.size(), "expired rows must not pair");
    }

    @Test
    void findNearDuplicatesScopesByUser() throws Exception {
        // Two duplicates belong to USER_ID; a third (with the same embedding)
        // belongs to a different user and must not appear in the result.
        final float[] vec = embedder.embed("seed");
        insertWithEmbedding("Mine A.", vec);
        insertWithEmbedding("Mine B.", vec);
        insertWithEmbeddingForUser("Other's.", vec, "different-user");

        final List<Object[]> pairs = repo.findNearDuplicatePairs(db, USER_ID, 0.9, 10);
        assertEquals(1, pairs.size(), "cross-user rows must not pair");
    }

    @Test
    void findNearDuplicatesOrdersBySimilarityDescending() throws Exception {
        // Two pairs at different similarity levels: identical-vector pair
        // is similarity 1.0; partially-shared-vector pair is somewhere in
        // between.  Build the partial-shared pair by mixing two seed
        // vectors so the cosine of the mix with the seed is well above
        // threshold but below 1.0.
        final float[] seed = embedder.embed("seed");
        final float[] mix  = mixVectors(seed, embedder.embed("partner"), 0.85f);

        final long a = insertWithEmbedding("First identical.",  seed);
        final long b = insertWithEmbedding("Second identical.", seed);
        final long c = insertWithEmbedding("Mixed seed.",       mix);

        final List<Object[]> pairs = repo.findNearDuplicatePairs(db, USER_ID, 0.5, 10);
        assertTrue(pairs.size() >= 2);
        // First (highest-sim) pair must be the {a, b} identical-vector pair.
        final double first  = ((Double) pairs.get(0)[2]).doubleValue();
        final double second = ((Double) pairs.get(1)[2]).doubleValue();
        assertTrue(first >= second, "results are sorted by similarity descending");
        assertEquals(1.0, first, 1e-3);
    }

    // ====================================================================================
    // MemoryFilter: cleanup-discovery filters on listRecent + count
    // ====================================================================================

    @Test
    void filterUntaggedOnlyExcludesTaggedRows() throws Exception {
        insert("tagged row.",   new String[]{"a"});
        insert("untagged row.", new String[]{});

        final ai.ownsona.memory.MemoryFilter f = new ai.ownsona.memory.MemoryFilter();
        f.untaggedOnly = true;

        final List<MemoryRow> rows = repo.listRecent(db, USER_ID, 10, 0, false, f);
        assertEquals(1, rows.size());
        assertEquals("untagged row.", rows.get(0).text);

        assertEquals(1L, repo.count(db, USER_ID, false, null, null, f));
    }

    @Test
    void filterMinCharsRespectsBoundary() throws Exception {
        insert("12345",      new String[]{});   // exactly 5
        insert("1234567890", new String[]{});   // 10

        final ai.ownsona.memory.MemoryFilter f = new ai.ownsona.memory.MemoryFilter();
        f.minChars = 6;

        final List<MemoryRow> rows = repo.listRecent(db, USER_ID, 10, 0, false, f);
        assertEquals(1, rows.size());
        assertEquals("1234567890", rows.get(0).text);
    }

    @Test
    void filterMaxCharsRespectsBoundary() throws Exception {
        insert("short.",                new String[]{});   // 6 chars
        insert("a longer text content.",new String[]{});   // 22 chars

        final ai.ownsona.memory.MemoryFilter f = new ai.ownsona.memory.MemoryFilter();
        f.maxChars = 10;

        final List<MemoryRow> rows = repo.listRecent(db, USER_ID, 10, 0, false, f);
        assertEquals(1, rows.size());
        assertEquals("short.", rows.get(0).text);
    }

    @Test
    void filterMinAndMaxCombineAsRange() throws Exception {
        insert("aaa",      new String[]{});         // 3
        insert("aaaaaaaa", new String[]{});         // 8
        insert("aaaaaaaaaaaaaaa", new String[]{});  // 15

        final ai.ownsona.memory.MemoryFilter f = new ai.ownsona.memory.MemoryFilter();
        f.minChars = 5;
        f.maxChars = 10;

        final List<MemoryRow> rows = repo.listRecent(db, USER_ID, 10, 0, false, f);
        assertEquals(1, rows.size());
        assertEquals("aaaaaaaa", rows.get(0).text);
    }

    @Test
    void filterNotConfirmedSinceIncludesNullsAndOlderRows() throws Exception {
        // Row 1: never confirmed.
        final long neverId = insert("never confirmed.", new String[]{});

        // Row 2: confirmed in the past (older than the threshold).
        final long oldId = insert("confirmed long ago.", new String[]{});
        db.execute("UPDATE memories SET last_confirmed_at = now() - interval '30 days' WHERE id = ?", oldId);

        // Row 3: confirmed recently (within threshold).
        final long freshId = insert("confirmed recently.", new String[]{});
        db.execute("UPDATE memories SET last_confirmed_at = now() WHERE id = ?", freshId);

        // Threshold: rows not confirmed since 7 days ago.
        final ai.ownsona.memory.MemoryFilter f = new ai.ownsona.memory.MemoryFilter();
        f.notConfirmedSince = new java.util.Date(System.currentTimeMillis() - 7L * 24L * 3600_000L);

        final List<MemoryRow> rows = repo.listRecent(db, USER_ID, 10, 0, false, f);
        assertEquals(2, rows.size(), "should include the never-confirmed row AND the old-confirmed row");
        final java.util.Set<Long> got = new java.util.HashSet<>();
        for (MemoryRow r : rows)
            got.add(r.id);
        assertTrue(got.contains(neverId));
        assertTrue(got.contains(oldId));
        assertFalse(got.contains(freshId));

        assertEquals(2L, repo.count(db, USER_ID, false, null, null, f));
    }

    @Test
    void filterCombinesUntaggedAndMaxChars() throws Exception {
        insert("a long untagged sentence that exceeds the cap.", new String[]{});
        insert("frag.",                                          new String[]{});  // untagged, 5 chars
        insert("short tagged.",                                  new String[]{"a"});

        final ai.ownsona.memory.MemoryFilter f = new ai.ownsona.memory.MemoryFilter();
        f.untaggedOnly = true;
        f.maxChars     = 10;

        final List<MemoryRow> rows = repo.listRecent(db, USER_ID, 10, 0, false, f);
        assertEquals(1, rows.size());
        assertEquals("frag.", rows.get(0).text);
    }

    @Test
    void filterNullSameAsNoFilter() throws Exception {
        insert("a", new String[]{"x"});
        insert("b", new String[]{});

        final long withFilter    = repo.count(db, USER_ID, false, null, null, null);
        final long withoutFilter = repo.count(db, USER_ID, false, null, null);
        assertEquals(2L, withoutFilter);
        assertEquals(withoutFilter, withFilter,
                "passing null filter must match the no-filter overload");
    }

    private long insertWithEmbedding(String text, float[] embedding) throws Exception {
        return insertWithEmbeddingForUser(text, embedding, USER_ID);
    }

    private long insertWithEmbeddingForUser(String text, float[] embedding, String userId) throws Exception {
        final MemoryInsert m = new MemoryInsert();
        m.userId            = userId;
        m.text              = text;
        m.normalizedText    = TextNormalizer.normalize(text);
        m.embedding         = embedding;
        m.tags              = new String[]{};
        m.importance        = 0.5;
        m.embeddingProvider = "mock";
        m.embeddingModel    = embedder.modelName();
        return repo.insert(db, m);
    }

    private static float[] mixVectors(float[] a, float[] b, float wA) throws Exception {
        if (a.length != b.length)
            throw new IllegalArgumentException("vector length mismatch");
        final float[] out = new float[a.length];
        double sumSq = 0;
        for (int i = 0; i < a.length; i++) {
            out[i] = wA * a[i] + (1 - wA) * b[i];
            sumSq += out[i] * out[i];
        }
        final double norm = Math.sqrt(sumSq);
        if (norm > 0)
            for (int i = 0; i < a.length; i++)
                out[i] = (float) (out[i] / norm);
        return out;
    }

    private long insertWithSource(String text, String provider) throws Exception {
        final MemoryInsert m = new MemoryInsert();
        m.userId            = USER_ID;
        m.text              = text;
        m.normalizedText    = TextNormalizer.normalize(text);
        m.embedding         = embedder.embed(text);
        m.tags              = new String[]{};
        m.importance        = 0.5;
        m.sourceProvider    = provider;
        m.embeddingProvider = "mock";
        m.embeddingModel    = embedder.modelName();
        return repo.insert(db, m);
    }

    // -------------------------------------------------------------------------------

    private long insertWithProvider(String text, String provider, String model) throws Exception {
        final MemoryInsert m = new MemoryInsert();
        m.userId            = USER_ID;
        m.text              = text;
        m.normalizedText    = TextNormalizer.normalize(text);
        m.embedding         = embedder.embed(text);
        m.tags              = new String[]{};
        m.importance        = 0.5;
        m.embeddingProvider = provider;
        m.embeddingModel    = model;
        return repo.insert(db, m);
    }

    // -------------------------------------------------------------------------------

    private long insert(String text, String[] tags) throws Exception {
        final MemoryInsert m = new MemoryInsert();
        m.userId               = USER_ID;
        m.text                 = text;
        m.normalizedText       = TextNormalizer.normalize(text);
        m.embedding            = embedder.embed(text);
        m.tags                 = tags;
        m.importance           = 0.5;
        m.embeddingProvider    = "mock";
        m.embeddingModel       = embedder.modelName();
        return repo.insert(db, m);
    }

    private static java.sql.Connection openJdbc(String dbUrl) throws Exception {
        final String stripped = dbUrl.startsWith("jdbc:") ? dbUrl.substring(5) : dbUrl;
        final URI u = URI.create(stripped);
        final String host = u.getHost();
        final int port = u.getPort() > 0 ? u.getPort() : 5432;
        String dbname = u.getPath();
        if (dbname != null && dbname.startsWith("/"))
            dbname = dbname.substring(1);
        final String jdbc = "jdbc:postgresql://" + host + ":" + port + "/" + dbname;

        String user = null, pass = null;
        final String userInfo = u.getUserInfo();
        if (userInfo != null) {
            final int colon = userInfo.indexOf(':');
            if (colon < 0) {
                user = URLDecoder.decode(userInfo, StandardCharsets.UTF_8);
            } else {
                user = URLDecoder.decode(userInfo.substring(0, colon), StandardCharsets.UTF_8);
                pass = URLDecoder.decode(userInfo.substring(colon + 1), StandardCharsets.UTF_8);
            }
        }
        return DriverManager.getConnection(jdbc, user, pass);
    }
}
