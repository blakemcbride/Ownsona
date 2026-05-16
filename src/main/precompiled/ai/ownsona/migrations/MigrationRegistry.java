package ai.ownsona.migrations;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Ordered list of {@link Migration}s the application code expects to be
 * applied, plus the {@link #CURRENT_DB_VERSION} constant the code as a
 * whole is built against.
 *
 * <p><strong>Guardrail #11 of the rollout plan:</strong> whenever a new
 * migration class is added here, {@link #CURRENT_DB_VERSION} MUST be
 * bumped to match, in the same commit.  {@link #validate()} catches the
 * mismatch at server-startup time but the right place to notice is
 * code review.
 *
 * <p>Phase 2 ships an empty registry --- the baseline established by
 * {@code sql/001_init.sql} is version 1, and there is no migration yet
 * that takes the database past 1.  Phase 3 adds the first one.
 */
public final class MigrationRegistry {

    /**
     * The database version this build of the application expects.
     * Bump when you add a new {@link Migration} below.
     */
    public static final int CURRENT_DB_VERSION = 2;

    private static final List<Migration> MIGRATIONS;
    static {
        final List<Migration> m = new ArrayList<>();
        m.add(new Migration002AddRecordVersion());
        // Phase 4 will add:  m.add(new Migration003AddFreshness());
        // Phase 5 will add:  m.add(new Migration004AddTombstones());
        MIGRATIONS = Collections.unmodifiableList(m);
    }

    private MigrationRegistry() {
    }

    /** Returns the registered migrations in version order. */
    public static List<Migration> all() {
        return MIGRATIONS;
    }

    /**
     * Validate the registry: registered versions must be exactly
     * {@code 2, 3, …, CURRENT_DB_VERSION} --- contiguous, in order, no
     * gaps, no duplicates.  An empty registry is valid iff
     * {@code CURRENT_DB_VERSION == 1}.
     *
     * @throws IllegalStateException on any invariant violation.  This is
     *     a programmer error and should fail at server-init time
     *     (guardrail #11).
     */
    public static void validate() {
        validate(MIGRATIONS, CURRENT_DB_VERSION);
    }

    /**
     * Validation core, exposed package-private so unit tests can drive
     * the rule with arbitrary inputs.
     */
    static void validate(List<Migration> migrations, int targetVersion) {
        if (targetVersion < 1)
            throw new IllegalStateException(
                    "CURRENT_DB_VERSION must be >= 1 (got " + targetVersion + ").");

        final int expectedCount = targetVersion - 1;
        if (migrations.size() != expectedCount)
            throw new IllegalStateException(
                    "Registry has " + migrations.size() + " migrations but " +
                    "CURRENT_DB_VERSION = " + targetVersion + " expects " +
                    expectedCount + ".  Either add the missing migration(s) " +
                    "or bump CURRENT_DB_VERSION (guardrail #11).");

        for (int i = 0; i < migrations.size(); i++) {
            final Migration m = migrations.get(i);
            if (m == null)
                throw new IllegalStateException(
                        "Migration at index " + i + " is null.");
            final int expected = i + 2;
            if (m.version() != expected)
                throw new IllegalStateException(
                        "Migration at index " + i + " has version " +
                        m.version() + ", expected " + expected +
                        " (migrations must be 2, 3, …, " + targetVersion +
                        " in order with no gaps or duplicates).");
            if (m.name() == null || m.name().isEmpty())
                throw new IllegalStateException(
                        "Migration v=" + m.version() + " has empty name.");
        }
    }
}
