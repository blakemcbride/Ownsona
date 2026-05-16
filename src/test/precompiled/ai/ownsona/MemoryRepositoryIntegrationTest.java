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
                "mock", "mock-sha256");
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
        assertTrue(repo.softDelete(db, id));

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
        assertTrue(repo.softDelete(db, id));
        // Second call: row is already deleted; method returns whether it ended up deleted, which is still true.
        assertTrue(repo.softDelete(db, id));

        // update on a soft-deleted row should not "resurrect" it.
        final boolean updated = repo.update(db, id, "new text", "new text",
                embedder.embed("new text"), null, null, "mock", "mock-sha256");
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
