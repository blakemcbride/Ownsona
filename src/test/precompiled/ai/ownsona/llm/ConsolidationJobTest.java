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

    // -------------------------------------------------------------------
    // parseConflictDecision
    // -------------------------------------------------------------------

    @Test
    void parsesConflictResolution() {
        final ConsolidationJob.ConflictDecision d = ConsolidationJob.parseConflictDecision(
                "{\"conflict\": true, \"keep_id\": 17, \"supersede_ids\": [42, 43]}");
        assertTrue(d.conflict);
        assertEquals(Long.valueOf(17), d.keepId);
        assertEquals(Arrays.asList(42L, 43L), d.supersedeIds);
    }

    @Test
    void parsesNoConflict() {
        final ConsolidationJob.ConflictDecision d =
                ConsolidationJob.parseConflictDecision("{\"conflict\": false}");
        assertFalse(d.conflict);
        assertTrue(d.supersedeIds.isEmpty());
    }

    @Test
    void garbledConflictReplyIsNoOp() {
        assertFalse(ConsolidationJob.parseConflictDecision("not json").conflict);
        assertFalse(ConsolidationJob.parseConflictDecision(null).conflict);
    }

    // -------------------------------------------------------------------
    // isValidConflictResolution --- guards against ids outside the cluster
    // -------------------------------------------------------------------

    @Test
    void validResolutionAccepted() {
        final List<MemoryRow> members = Arrays.asList(
                row(17, "Austin", "U", 0.5), row(42, "Dallas", "U", 0.5));
        final ConsolidationJob.ConflictDecision d =
                new ConsolidationJob.ConflictDecision(true, 17L, Arrays.asList(42L));
        assertTrue(ConsolidationJob.isValidConflictResolution(d, members));
    }

    @Test
    void rejectsKeepIdOutsideCluster() {
        final List<MemoryRow> members = Arrays.asList(
                row(17, "Austin", "U", 0.5), row(42, "Dallas", "U", 0.5));
        // keep_id 99 is not a member --- must be rejected so we never act on
        // a hallucinated id.
        final ConsolidationJob.ConflictDecision d =
                new ConsolidationJob.ConflictDecision(true, 99L, Arrays.asList(42L));
        assertFalse(ConsolidationJob.isValidConflictResolution(d, members));
    }

    @Test
    void rejectsSupersedeIdOutsideClusterOrEqualToKeep() {
        final List<MemoryRow> members = Arrays.asList(
                row(17, "Austin", "U", 0.5), row(42, "Dallas", "U", 0.5));
        // supersede id 99 not in cluster.
        assertFalse(ConsolidationJob.isValidConflictResolution(
                new ConsolidationJob.ConflictDecision(true, 17L, Arrays.asList(99L)), members));
        // supersede id equals keep id.
        assertFalse(ConsolidationJob.isValidConflictResolution(
                new ConsolidationJob.ConflictDecision(true, 17L, Arrays.asList(17L)), members));
        // empty supersede list.
        assertFalse(ConsolidationJob.isValidConflictResolution(
                new ConsolidationJob.ConflictDecision(true, 17L, Arrays.asList()), members));
    }

    @Test
    void conflictUserMessageIncludesIds() {
        final List<MemoryRow> members = Arrays.asList(
                row(17, "Blake lives in Austin.", "U", 0.5),
                row(42, "Blake lives in Dallas.", "U", 0.5));
        final String msg = ConsolidationJob.buildConflictUserMessage(members);
        assertTrue(msg.contains("id 17"));
        assertTrue(msg.contains("id 42"));
        assertTrue(msg.contains("Austin"));
    }
}
