package ai.ownsona.memory;

/**
 * One per-input result from {@link MemoryService#updateBatch}.
 *
 * <p>{@link #inputIndex} is the original position in the request's items
 * array so the caller can correlate results to inputs even when items
 * fail individually.  {@link #id} echoes the id that was processed (null
 * when the input slot itself was null or omitted id).
 *
 * <p>{@link #changedFields} lists the field names the caller asked to
 * change for this item (text, tags, importance, expires_at,
 * last_confirmed_at).  See {@link UpdateResult#changedFields} for the
 * same "fields the caller specified" semantics.
 */
public final class BatchUpdateResult {
    public final int      inputIndex;
    public final Long     id;                // null when input slot lacked id
    public final boolean  ok;
    public final boolean  dryRun;            // mirrors the batch-level flag
    public final String[] changedFields;     // non-null when ok
    public final String   errorCode;         // non-null when !ok
    public final String   errorMessage;      // non-null when !ok

    private BatchUpdateResult(int inputIndex, Long id, boolean ok, boolean dryRun,
                              String[] changedFields, String errorCode, String errorMessage) {
        this.inputIndex    = inputIndex;
        this.id            = id;
        this.ok            = ok;
        this.dryRun        = dryRun;
        this.changedFields = changedFields;
        this.errorCode     = errorCode;
        this.errorMessage  = errorMessage;
    }

    public static BatchUpdateResult success(int inputIndex, long id, boolean dryRun, String[] changedFields) {
        return new BatchUpdateResult(inputIndex, id, true, dryRun, changedFields, null, null);
    }

    public static BatchUpdateResult failure(int inputIndex, Long id, String errorCode, String errorMessage) {
        return new BatchUpdateResult(inputIndex, id, false, false, null, errorCode, errorMessage);
    }
}
