package ai.ownsona.memory;

/**
 * Outcome of a single {@link MemoryService#update} call.
 *
 * <p>Carries enough state for the MCP layer to render an accurate response:
 * which fields the caller asked to change, whether the call was a dry-run,
 * and the row itself.  When {@code dryRun} is true the row reflects the
 * pre-update state (so the caller can preview); when {@code dryRun} is
 * false it reflects the post-update state (so the caller can render the
 * new values).
 *
 * <p>{@link #changedFields} lists the fields the caller supplied non-null
 * values for, regardless of whether the new value happens to equal the
 * old.  This matches what the caller asked the tool to do, not a
 * value-level diff (which would require comparing tag arrays, importance
 * doubles, etc., for surprisingly little value).
 */
public final class UpdateResult {
    public final boolean   ok;
    public final boolean   dryRun;
    public final MemoryRow row;
    public final String[]  changedFields;

    public UpdateResult(boolean ok, boolean dryRun, MemoryRow row, String[] changedFields) {
        this.ok            = ok;
        this.dryRun        = dryRun;
        this.row           = row;
        this.changedFields = changedFields;
    }
}
