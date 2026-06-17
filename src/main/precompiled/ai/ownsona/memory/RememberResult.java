package ai.ownsona.memory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Outcome of a {@link MemoryService#remember} call.
 *
 * <p>{@link #alreadyExisted} is true when an active memory with the same
 * normalized text was already stored, OR when a semantic-dedup check
 * matched a near-duplicate above the similarity threshold AND the
 * caller's dedup policy is {@code skip_if_near} --- in both cases the
 * caller is returned the existing id rather than getting a duplicate
 * inserted.
 *
 * <p>{@link #candidates} carries the rows the semantic-dedup check
 * flagged as near-duplicates (empty if none, or if dedup was skipped
 * via {@code dedup_policy = "insert"}).  Useful when the policy is
 * {@code ask} (default): the row IS inserted but the client also sees
 * what looked similar.
 */
public final class RememberResult {
    public final long             id;
    public final boolean          alreadyExisted;
    public final List<MemoryRow>  candidates;
    public final List<MemoryRow>  previouslyCorrected;

    /**
     * Active rows that look like potential <em>conflicts</em> with the new
     * memory: semantically close AND sharing a tag, but not already in
     * {@link #candidates}.  Surfaced so the caller can decide whether the
     * new fact corrects one of them (pure embedding+tag heuristic --- the
     * server does not judge contradiction).  Empty unless conflict
     * detection found something.
     */
    public final List<MemoryRow>  potentialConflicts;

    /**
     * Outcome of any explicit {@code supersedes} request: for each id the
     * caller asked to supersede, whether it was soft-deleted, skipped
     * because it was protected ({@code keep='Y'}), or not found.  Empty
     * when the caller didn't ask to supersede anything.
     */
    public final List<SupersedeOutcome> superseded;

    /** Per-id result of an explicit supersede (correction) request. */
    public static final class SupersedeOutcome {
        public static final String SUPERSEDED = "superseded";
        public static final String PROTECTED  = "protected";
        public static final String NOT_FOUND  = "not_found";

        public final long   id;
        public final String status;

        public SupersedeOutcome(long id, String status) {
            this.id     = id;
            this.status = status;
        }
    }

    public RememberResult(long id, boolean alreadyExisted) {
        this(id, alreadyExisted, Collections.emptyList(), Collections.emptyList());
    }

    public RememberResult(long id, boolean alreadyExisted, List<MemoryRow> candidates) {
        this(id, alreadyExisted, candidates, Collections.emptyList());
    }

    public RememberResult(long id, boolean alreadyExisted,
                          List<MemoryRow> candidates, List<MemoryRow> previouslyCorrected) {
        this(id, alreadyExisted, candidates, previouslyCorrected,
                Collections.emptyList(), Collections.emptyList());
    }

    public RememberResult(long id, boolean alreadyExisted,
                          List<MemoryRow> candidates, List<MemoryRow> previouslyCorrected,
                          List<MemoryRow> potentialConflicts,
                          List<SupersedeOutcome> superseded) {
        this.id                  = id;
        this.alreadyExisted      = alreadyExisted;
        this.candidates          = (candidates == null)
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(candidates));
        this.previouslyCorrected = (previouslyCorrected == null)
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(previouslyCorrected));
        this.potentialConflicts  = (potentialConflicts == null)
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(potentialConflicts));
        this.superseded          = (superseded == null)
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(superseded));
    }
}
