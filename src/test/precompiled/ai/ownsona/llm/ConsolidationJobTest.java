package ai.ownsona.llm;

import ai.ownsona.memory.MemoryRow;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the pure helpers of the Tier 3 consolidation job:
 * merge-reply parsing, JSON extraction, prompt building, and cluster
 * aggregation.  No DB or network.
 */
class ConsolidationJobTest {

    private static MemoryRow row(long id, String text, String keep, double importance, String... tags) {
        final MemoryRow m = new MemoryRow();
        m.id         = id;
        m.text       = text;
        m.keep       = keep;
        m.importance = importance;
        m.tags       = tags;
        return m;
    }

    // -------------------------------------------------------------------
    // parseMergeDecision
    // -------------------------------------------------------------------

    @Test
    void parsesMergeTrueWithText() {
        final ConsolidationJob.MergeDecision d =
                ConsolidationJob.parseMergeDecision("{\"merge\": true, \"text\": \"Blake lives in Austin.\"}");
        assertTrue(d.merge);
        assertEquals("Blake lives in Austin.", d.text);
    }

    @Test
    void parsesMergeFalse() {
        final ConsolidationJob.MergeDecision d =
                ConsolidationJob.parseMergeDecision("{\"merge\": false}");
        assertFalse(d.merge);
        assertNull(d.text);
    }

    @Test
    void mergeTrueButEmptyTextIsNotAMerge() {
        // A merge with no usable text must not supersede anything.
        final ConsolidationJob.MergeDecision d =
                ConsolidationJob.parseMergeDecision("{\"merge\": true, \"text\": \"   \"}");
        assertFalse(d.merge);
    }

    @Test
    void toleratesProseAndMarkdownAroundJson() {
        final String reply = "Sure! Here is the result:\n```json\n" +
                "{\"merge\": true, \"text\": \"Two facts merged.\"}\n```\nHope that helps.";
        final ConsolidationJob.MergeDecision d = ConsolidationJob.parseMergeDecision(reply);
        assertTrue(d.merge);
        assertEquals("Two facts merged.", d.text);
    }

    @Test
    void garbledReplyDefaultsToNoMerge() {
        assertFalse(ConsolidationJob.parseMergeDecision("not json at all").merge);
        assertFalse(ConsolidationJob.parseMergeDecision("").merge);
        assertFalse(ConsolidationJob.parseMergeDecision(null).merge);
    }

    // -------------------------------------------------------------------
    // extractJsonObject
    // -------------------------------------------------------------------

    @Test
    void extractsBalancedObjectIgnoringBracesInStrings() {
        final String s = "prefix {\"text\": \"a } brace { inside\", \"merge\": true} suffix";
        assertEquals("{\"text\": \"a } brace { inside\", \"merge\": true}",
                ConsolidationJob.extractJsonObject(s));
    }

    @Test
    void extractsNestedObject() {
        final String s = "{\"a\": {\"b\": 1}, \"merge\": false}";
        assertEquals(s, ConsolidationJob.extractJsonObject(s));
    }

    @Test
    void extractReturnsNullWhenNoObject() {
        assertNull(ConsolidationJob.extractJsonObject("no braces here"));
        assertNull(ConsolidationJob.extractJsonObject(null));
    }

    // -------------------------------------------------------------------
    // buildMergeUserMessage
    // -------------------------------------------------------------------

    @Test
    void buildsNumberedStatementList() {
        final List<MemoryRow> members = Arrays.asList(
                row(1, "Blake lives in Austin.", "U", 0.5),
                row(2, "Blake's home is Austin, TX.", "U", 0.5));
        final String msg = ConsolidationJob.buildMergeUserMessage(members);
        assertTrue(msg.contains("1. Blake lives in Austin."));
        assertTrue(msg.contains("2. Blake's home is Austin, TX."));
    }

    // -------------------------------------------------------------------
    // unionTags / maxImportance / hasProtectedMember
    // -------------------------------------------------------------------

    @Test
    void unionTagsDedupesAcrossMembers() {
        final List<MemoryRow> members = Arrays.asList(
                row(1, "x", "U", 0.5, "home", "location"),
                row(2, "y", "U", 0.5, "location", "personal"));
        assertArrayEquals(new String[]{"home", "location", "personal"},
                ConsolidationJob.unionTags(members));
    }

    @Test
    void maxImportanceTakesHighest() {
        final List<MemoryRow> members = Arrays.asList(
                row(1, "x", "U", 0.3),
                row(2, "y", "U", 0.8),
                row(3, "z", "U", 0.5));
        assertEquals(0.8, ConsolidationJob.maxImportance(members), 1e-9);
    }

    @Test
    void detectsProtectedMember() {
        assertTrue(ConsolidationJob.hasProtectedMember(Arrays.asList(
                row(1, "x", "U", 0.5), row(2, "y", "Y", 0.5))));
        assertFalse(ConsolidationJob.hasProtectedMember(Arrays.asList(
                row(1, "x", "U", 0.5), row(2, "y", "N", 0.5))));
    }
}
