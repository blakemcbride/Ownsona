package ai.ownsona.migrations;

import org.junit.jupiter.api.Test;
import org.kissweb.database.Connection;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the package-private MigrationRegistry.validate core.
 * Exercises the contract without touching a database.
 */
class MigrationRegistryTest {

    @Test
    void emptyRegistryWithTargetOneIsValid() {
        assertDoesNotThrow(() ->
                MigrationRegistry.validate(Collections.emptyList(), 1));
    }

    @Test
    void emptyRegistryWithTargetGreaterThanOneFails() {
        final IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> MigrationRegistry.validate(Collections.emptyList(), 2));
        assertTrue(e.getMessage().contains("0 migrations"),
                "message should mention the size mismatch: " + e.getMessage());
    }

    @Test
    void singleMigrationAtVersionTwoValidWhenTargetIsTwo() {
        final List<Migration> ms = Collections.singletonList(stub(2, "add foo"));
        assertDoesNotThrow(() -> MigrationRegistry.validate(ms, 2));
    }

    @Test
    void singleMigrationAtVersionThreeWithTargetTwoFailsOnCount() {
        final List<Migration> ms = Collections.singletonList(stub(3, "add foo"));
        assertThrows(IllegalStateException.class,
                () -> MigrationRegistry.validate(ms, 2));
    }

    @Test
    void singleMigrationAtVersionThreeWithTargetThreeFailsOnGap() {
        // expected: [v=2, v=3]; got: [v=3] alone --- count is 1 but
        // expectedCount = 2.  The count check fires first.
        final List<Migration> ms = Collections.singletonList(stub(3, "add foo"));
        assertThrows(IllegalStateException.class,
                () -> MigrationRegistry.validate(ms, 3));
    }

    @Test
    void contiguousSequenceValidates() {
        final List<Migration> ms = Arrays.asList(
                stub(2, "two"),
                stub(3, "three"),
                stub(4, "four"));
        assertDoesNotThrow(() -> MigrationRegistry.validate(ms, 4));
    }

    @Test
    void outOfOrderRejected() {
        final List<Migration> ms = Arrays.asList(
                stub(2, "two"),
                stub(4, "four"),
                stub(3, "three"));
        final IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> MigrationRegistry.validate(ms, 4));
        assertTrue(e.getMessage().contains("version 4"),
                "should flag the out-of-order version: " + e.getMessage());
    }

    @Test
    void duplicateRejected() {
        final List<Migration> ms = Arrays.asList(
                stub(2, "two-a"),
                stub(2, "two-b"));
        // Two migrations with same version → second one at index 1 has
        // version 2 but expected is 3 → fails contiguous check.
        assertThrows(IllegalStateException.class,
                () -> MigrationRegistry.validate(ms, 3));
    }

    @Test
    void gapRejected() {
        // [v=2, v=4] with target 4: count matches (2 == 4-1-... wait, no.
        // expectedCount = target - 1 = 3.  Size is 2.  Count check fires.
        final List<Migration> ms = Arrays.asList(
                stub(2, "two"),
                stub(4, "four"));
        assertThrows(IllegalStateException.class,
                () -> MigrationRegistry.validate(ms, 4));
    }

    @Test
    void contiguousButTargetBelowMaxRejected() {
        final List<Migration> ms = Arrays.asList(
                stub(2, "two"),
                stub(3, "three"));
        // target=2 but two migrations registered → size mismatch.
        assertThrows(IllegalStateException.class,
                () -> MigrationRegistry.validate(ms, 2));
    }

    @Test
    void zeroTargetRejected() {
        assertThrows(IllegalStateException.class,
                () -> MigrationRegistry.validate(Collections.emptyList(), 0));
    }

    @Test
    void nullMigrationInListRejected() {
        final List<Migration> ms = Arrays.asList(stub(2, "two"), null);
        assertThrows(IllegalStateException.class,
                () -> MigrationRegistry.validate(ms, 3));
    }

    @Test
    void emptyNameRejected() {
        final List<Migration> ms = Collections.singletonList(stub(2, ""));
        assertThrows(IllegalStateException.class,
                () -> MigrationRegistry.validate(ms, 2));
    }

    @Test
    void shippedRegistryValidates() {
        // The actual registered set must itself satisfy the contract.
        // If it doesn't, the server refuses to start at clinit time ---
        // catch it here at test time instead.
        assertDoesNotThrow(() -> MigrationRegistry.validate());
    }

    // -------------------------------------------------------------------

    private static Migration stub(int version, String name) {
        return new Migration() {
            @Override public int    version() { return version; }
            @Override public String name()    { return name; }
            @Override public void apply(Connection db) { /* test stub */ }
        };
    }
}
