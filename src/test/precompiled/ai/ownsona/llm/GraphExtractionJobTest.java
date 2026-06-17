package ai.ownsona.llm;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the pure triple-parsing helper of the Tier 4 phase 2
 * graph-extraction job.  No DB or network.
 */
class GraphExtractionJobTest {

    @Test
    void parsesTriples() {
        final List<String[]> t = GraphExtractionJob.parseTriples(
                "{\"triples\": [" +
                "{\"subject\": \"Blake\", \"predicate\": \"lives in\", \"object\": \"Austin\"}," +
                "{\"subject\": \"Blake\", \"predicate\": \"works at\", \"object\": \"Acme\"}]}");
        assertEquals(2, t.size());
        assertArrayEquals(new String[]{"Blake", "lives in", "Austin"}, t.get(0));
        assertArrayEquals(new String[]{"Blake", "works at", "Acme"}, t.get(1));
    }

    @Test
    void emptyTriplesArrayYieldsEmptyList() {
        assertTrue(GraphExtractionJob.parseTriples("{\"triples\": []}").isEmpty());
    }

    @Test
    void dropsTriplesWithABlankField() {
        final List<String[]> t = GraphExtractionJob.parseTriples(
                "{\"triples\": [" +
                "{\"subject\": \"Blake\", \"predicate\": \"\", \"object\": \"Austin\"}," +
                "{\"subject\": \"Blake\", \"predicate\": \"likes\", \"object\": \"coffee\"}]}");
        assertEquals(1, t.size());
        assertArrayEquals(new String[]{"Blake", "likes", "coffee"}, t.get(0));
    }

    @Test
    void toleratesProseAndMarkdownAroundJson() {
        final String reply = "Here you go:\n```json\n" +
                "{\"triples\": [{\"subject\": \"A\", \"predicate\": \"knows\", \"object\": \"B\"}]}\n```";
        final List<String[]> t = GraphExtractionJob.parseTriples(reply);
        assertEquals(1, t.size());
        assertArrayEquals(new String[]{"A", "knows", "B"}, t.get(0));
    }

    @Test
    void garbledOrEmptyReplyYieldsEmptyList() {
        assertTrue(GraphExtractionJob.parseTriples("not json").isEmpty());
        assertTrue(GraphExtractionJob.parseTriples("").isEmpty());
        assertTrue(GraphExtractionJob.parseTriples(null).isEmpty());
        // Well-formed JSON but no triples key.
        assertTrue(GraphExtractionJob.parseTriples("{\"foo\": 1}").isEmpty());
    }
}
