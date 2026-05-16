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

    public RememberResult(long id, boolean alreadyExisted) {
        this(id, alreadyExisted, Collections.emptyList());
    }

    public RememberResult(long id, boolean alreadyExisted, List<MemoryRow> candidates) {
        this.id             = id;
        this.alreadyExisted = alreadyExisted;
        this.candidates     = (candidates == null)
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(candidates));
    }
}
