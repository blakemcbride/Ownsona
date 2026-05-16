package ai.ownsona.memory;

import org.junit.jupiter.api.Test;
import org.kissweb.database.Connection;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link RecordUpgraderRegistry}.  Exercises the
 * package-private validate(list, target) core with arbitrary inputs;
 * no database required.
 */
class RecordUpgraderRegistryTest {

    @Test
    void emptyRegistryWithTargetOneIsValid() {
        assertDoesNotThrow(() ->
                RecordUpgraderRegistry.validate(Collections.emptyList(), 1));
    }

    @Test
    void emptyRegistryWithTargetGreaterThanOneFails() {
        final IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> RecordUpgraderRegistry.validate(Collections.emptyList(), 2));
        assertTrue(e.getMessage().contains("0 upgraders"),
                "message should mention size mismatch: " + e.getMessage());
    }

    @Test
    void singleUpgraderAtFromOneToTwoValidWithTargetTwo() {
        final List<RecordUpgrader> us = Collections.singletonList(stub(1, 2, "noop-1-to-2"));
        assertDoesNotThrow(() -> RecordUpgraderRegistry.validate(us, 2));
    }

    @Test
    void contiguousSequenceValidates() {
        final List<RecordUpgrader> us = Arrays.asList(
                stub(1, 2, "one"),
                stub(2, 3, "two"),
                stub(3, 4, "three"));
        assertDoesNotThrow(() -> RecordUpgraderRegistry.validate(us, 4));
    }

    @Test
    void firstUpgraderMustStartAtFromOne() {
        // Single upgrader, target=2 → expectedCount=1, size matches.
        // The contiguity check (not the count check) catches the wrong
        // starting fromVersion=2.
        final List<RecordUpgrader> us = Collections.singletonList(stub(2, 3, "from-two"));
        final IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> RecordUpgraderRegistry.validate(us, 2));
        assertTrue(e.getMessage().contains("fromVersion="),
                "should flag wrong starting fromVersion: " + e.getMessage());
    }

    @Test
    void gapInFromVersionsRejected() {
        // [1→2, 3→4] with target 4 has the right count but a gap.
        final List<RecordUpgrader> us = Arrays.asList(
                stub(1, 2, "one"),
                stub(3, 4, "three"));
        // expectedCount = 3 (= target-1) but size=2 → caught by count check.
        assertThrows(IllegalStateException.class,
                () -> RecordUpgraderRegistry.validate(us, 4));
    }

    @Test
    void wrongToVersionRejected() {
        // 1→3 is invalid (each upgrader must bump by exactly one).
        final List<RecordUpgrader> us = Collections.singletonList(stub(1, 3, "skip"));
        final IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> RecordUpgraderRegistry.validate(us, 2));
        assertTrue(e.getMessage().contains("toVersion="),
                "should flag wrong toVersion: " + e.getMessage());
    }

    @Test
    void duplicateRejected() {
        final List<RecordUpgrader> us = Arrays.asList(
                stub(1, 2, "one-a"),
                stub(1, 2, "one-b"));
        // The second one's fromVersion=1 but expected at index 1 is 2.
        assertThrows(IllegalStateException.class,
                () -> RecordUpgraderRegistry.validate(us, 3));
    }

    @Test
    void zeroTargetRejected() {
        assertThrows(IllegalStateException.class,
                () -> RecordUpgraderRegistry.validate(Collections.emptyList(), 0));
    }

    @Test
    void nullEntryRejected() {
        final List<RecordUpgrader> us = Arrays.asList(stub(1, 2, "one"), null);
        assertThrows(IllegalStateException.class,
                () -> RecordUpgraderRegistry.validate(us, 3));
    }

    @Test
    void emptyNameRejected() {
        final List<RecordUpgrader> us = Collections.singletonList(stub(1, 2, ""));
        assertThrows(IllegalStateException.class,
                () -> RecordUpgraderRegistry.validate(us, 2));
    }

    @Test
    void shippedRegistryValidates() {
        // The actual registered set must satisfy the contract.
        assertDoesNotThrow(() -> RecordUpgraderRegistry.validate());
    }

    @Test
    void forFromVersionReturnsNullForUnknown() {
        // The shipped registry is empty, so any lookup returns null.
        assertNull(RecordUpgraderRegistry.forFromVersion(0));
        assertNull(RecordUpgraderRegistry.forFromVersion(1));
        assertNull(RecordUpgraderRegistry.forFromVersion(99));
    }

    // -------------------------------------------------------------------

    private static RecordUpgrader stub(int from, int to, String name) {
        return new RecordUpgrader() {
            @Override public int    fromVersion() { return from; }
            @Override public int    toVersion()   { return to; }
            @Override public String name()        { return name; }
            @Override public void   upgrade(Connection db, MemoryRow row) {
                /* test stub */
            }
        };
    }
}
