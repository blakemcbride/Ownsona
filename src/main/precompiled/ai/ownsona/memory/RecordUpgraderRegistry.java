package ai.ownsona.memory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Ordered list of {@link RecordUpgrader}s the running code expects to
 * apply at startup, plus the {@link #CURRENT_RECORD_VERSION} the code
 * is built against.
 *
 * <p>Distinct from {@code ai.ownsona.migrations.MigrationRegistry}:
 * that one tracks <em>database-wide</em> schema versions (the
 * {@code db_version} table); this one tracks <em>per-row</em>
 * versions (the {@code record_version} column on each memory).
 * Both auto-apply on startup, in that order --- DB migrations first
 * (because they may create the {@code record_version} column or
 * columns the upgraders need), then per-record upgraders.
 *
 * <p>Phase 3 ships this empty (no upgraders, target=1).  Future
 * feature phases will register upgraders here and bump the constant.
 */
public final class RecordUpgraderRegistry {

    /**
     * The record_version this build expects every memory to be at.
     * Bump when you add a new {@link RecordUpgrader} below.
     */
    public static final int CURRENT_RECORD_VERSION = 1;

    private static final List<RecordUpgrader> UPGRADERS;
    static {
        final List<RecordUpgrader> u = new ArrayList<>();
        // Future:
        //   u.add(new MyV1ToV2Upgrader());   // when CURRENT_RECORD_VERSION = 2
        //   u.add(new MyV2ToV3Upgrader());   // when CURRENT_RECORD_VERSION = 3
        UPGRADERS = Collections.unmodifiableList(u);
    }

    private RecordUpgraderRegistry() {
    }

    /** Returns the registered upgraders in version order (fromVersion ascending). */
    public static List<RecordUpgrader> all() {
        return UPGRADERS;
    }

    /**
     * Return the upgrader whose {@code fromVersion()} equals the given
     * value, or null if none is registered.  By the registry's contract
     * (enforced by {@link #validate()}) the upgrader at index N-1 is
     * the one that takes a row from version N to N+1.
     */
    public static RecordUpgrader forFromVersion(int from) {
        final int idx = from - 1;
        if (idx < 0 || idx >= UPGRADERS.size())
            return null;
        final RecordUpgrader r = UPGRADERS.get(idx);
        return (r != null && r.fromVersion() == from) ? r : null;
    }

    /**
     * Validate the registry: registered upgraders must form a contiguous
     * sequence with fromVersion = 1, 2, …, CURRENT_RECORD_VERSION-1 and
     * toVersion = fromVersion + 1.  An empty registry is valid iff
     * {@code CURRENT_RECORD_VERSION == 1}.
     *
     * @throws IllegalStateException on any invariant violation.  This is
     *     a programmer error and fails at server-init time.
     */
    public static void validate() {
        validate(UPGRADERS, CURRENT_RECORD_VERSION);
    }

    /** Validation core, exposed package-private so unit tests can drive it. */
    static void validate(List<RecordUpgrader> upgraders, int targetVersion) {
        if (targetVersion < 1)
            throw new IllegalStateException(
                    "CURRENT_RECORD_VERSION must be >= 1 (got " + targetVersion + ").");

        final int expectedCount = targetVersion - 1;
        if (upgraders.size() != expectedCount)
            throw new IllegalStateException(
                    "Registry has " + upgraders.size() + " upgraders but " +
                    "CURRENT_RECORD_VERSION = " + targetVersion + " expects " +
                    expectedCount + ".");

        for (int i = 0; i < upgraders.size(); i++) {
            final RecordUpgrader r = upgraders.get(i);
            if (r == null)
                throw new IllegalStateException(
                        "RecordUpgrader at index " + i + " is null.");
            final int expectedFrom = i + 1;
            if (r.fromVersion() != expectedFrom)
                throw new IllegalStateException(
                        "RecordUpgrader at index " + i + " has fromVersion=" +
                        r.fromVersion() + ", expected " + expectedFrom +
                        " (upgraders must form a contiguous 1, 2, …, " +
                        (targetVersion - 1) + " sequence with no gaps).");
            if (r.toVersion() != expectedFrom + 1)
                throw new IllegalStateException(
                        "RecordUpgrader fromVersion=" + r.fromVersion() +
                        " has toVersion=" + r.toVersion() + ", expected " +
                        (expectedFrom + 1) + " (each upgrader must bump " +
                        "version by exactly one).");
            if (r.name() == null || r.name().isEmpty())
                throw new IllegalStateException(
                        "RecordUpgrader fromVersion=" + r.fromVersion() +
                        " has empty name.");
        }
    }
}
