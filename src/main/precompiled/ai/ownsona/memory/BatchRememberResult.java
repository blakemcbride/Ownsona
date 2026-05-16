package ai.ownsona.memory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One per-input result from {@link MemoryService#rememberBatch}.
 *
 * <p>{@link #inputIndex} is the original position in the request's items
 * array, so the caller can correlate results to inputs even when items are
 * filtered, reordered, or fail individually.
 *
 * <p>{@link #candidates} carries near-duplicates flagged by the
 * semantic-dedup check for this item (empty if none or if dedup was
 * skipped via {@code dedup_policy = "insert"}).  Same semantics as
 * {@link RememberResult#candidates}.
 */
public final class BatchRememberResult {
    public final int             inputIndex;
    public final boolean         ok;
    public final Long            memoryId;          // non-null when ok
    public final boolean         alreadyExisted;    // meaningful when ok
    public final String          errorCode;         // non-null when !ok
    public final String          errorMessage;      // non-null when !ok
    public final List<MemoryRow> candidates;        // non-null; possibly empty

    private BatchRememberResult(int inputIndex, boolean ok, Long memoryId,
                                boolean alreadyExisted, String errorCode, String errorMessage,
                                List<MemoryRow> candidates) {
        this.inputIndex     = inputIndex;
        this.ok             = ok;
        this.memoryId       = memoryId;
        this.alreadyExisted = alreadyExisted;
        this.errorCode      = errorCode;
        this.errorMessage   = errorMessage;
        this.candidates     = (candidates == null)
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(candidates));
    }

    public static BatchRememberResult success(int inputIndex, long memoryId, boolean alreadyExisted) {
        return new BatchRememberResult(inputIndex, true, memoryId, alreadyExisted, null, null, null);
    }

    public static BatchRememberResult success(int inputIndex, long memoryId, boolean alreadyExisted,
                                              List<MemoryRow> candidates) {
        return new BatchRememberResult(inputIndex, true, memoryId, alreadyExisted, null, null, candidates);
    }

    public static BatchRememberResult failure(int inputIndex, String errorCode, String errorMessage) {
        return new BatchRememberResult(inputIndex, false, null, false, errorCode, errorMessage, null);
    }
}
