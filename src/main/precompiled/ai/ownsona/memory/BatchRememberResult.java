package ai.ownsona.memory;

/**
 * One per-input result from {@link MemoryService#rememberBatch}.
 *
 * <p>{@link #inputIndex} is the original position in the request's items
 * array, so the caller can correlate results to inputs even when items are
 * filtered, reordered, or fail individually.
 */
public final class BatchRememberResult {
    public final int     inputIndex;
    public final boolean ok;
    public final Long    memoryId;          // non-null when ok
    public final boolean alreadyExisted;    // meaningful when ok
    public final String  errorCode;         // non-null when !ok
    public final String  errorMessage;      // non-null when !ok

    private BatchRememberResult(int inputIndex, boolean ok, Long memoryId,
                                boolean alreadyExisted, String errorCode, String errorMessage) {
        this.inputIndex     = inputIndex;
        this.ok             = ok;
        this.memoryId       = memoryId;
        this.alreadyExisted = alreadyExisted;
        this.errorCode      = errorCode;
        this.errorMessage   = errorMessage;
    }

    public static BatchRememberResult success(int inputIndex, long memoryId, boolean alreadyExisted) {
        return new BatchRememberResult(inputIndex, true, memoryId, alreadyExisted, null, null);
    }

    public static BatchRememberResult failure(int inputIndex, String errorCode, String errorMessage) {
        return new BatchRememberResult(inputIndex, false, null, false, errorCode, errorMessage);
    }
}
