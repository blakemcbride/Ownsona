package ai.ownsona.memory;

/**
 * Outcome of a single {@link MemoryService#forget} call.
 *
 * <p>Carries enough state for the MCP layer to render an accurate response
 * --- in particular, whether the call was a {@code dry_run} and whether the
 * row was already soft-deleted before this call.  The single-row contract
 * was previously a plain {@code boolean}; it grew when {@code dry_run} was
 * added because callers need to know "would have done X" vs "did X".
 */
public final class ForgetResult {
    public final boolean ok;
    public final boolean dryRun;
    public final boolean alreadyDeleted;
    public final boolean hardDeleted;

    public ForgetResult(boolean ok, boolean dryRun, boolean alreadyDeleted, boolean hardDeleted) {
        this.ok             = ok;
        this.dryRun         = dryRun;
        this.alreadyDeleted = alreadyDeleted;
        this.hardDeleted    = hardDeleted;
    }
}
