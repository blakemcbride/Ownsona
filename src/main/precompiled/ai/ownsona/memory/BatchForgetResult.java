package ai.ownsona.memory;

/**
 * One per-input result from {@link MemoryService#forgetBatch}.
 *
 * <p>{@link #inputIndex} is the original position in the request's ids
 * array so the caller can correlate results to inputs even when items
 * fail individually.  {@link #id} echoes the id that was processed
 * (null when the input slot itself was null).
 *
 * <p>{@code forget_batch} is soft-delete only; {@code hardDelete} is not
 * part of this contract on purpose --- a bulk hard delete has no
 * tombstone trail and is reserved for the single-row tool.
 */
public final class BatchForgetResult {
    public final int    inputIndex;
    public final Long   id;                  // null when input slot was null
    public final boolean ok;
    public final boolean alreadyDeleted;     // meaningful when ok
    public final String errorCode;           // non-null when !ok
    public final String errorMessage;        // non-null when !ok

    private BatchForgetResult(int inputIndex, Long id, boolean ok, boolean alreadyDeleted,
                              String errorCode, String errorMessage) {
        this.inputIndex     = inputIndex;
        this.id             = id;
        this.ok             = ok;
        this.alreadyDeleted = alreadyDeleted;
        this.errorCode      = errorCode;
        this.errorMessage   = errorMessage;
    }

    public static BatchForgetResult success(int inputIndex, long id, boolean alreadyDeleted) {
        return new BatchForgetResult(inputIndex, id, true, alreadyDeleted, null, null);
    }

    public static BatchForgetResult failure(int inputIndex, Long id, String errorCode, String errorMessage) {
        return new BatchForgetResult(inputIndex, id, false, false, errorCode, errorMessage);
    }
}
