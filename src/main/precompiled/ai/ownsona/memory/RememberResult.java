package ai.ownsona.memory;

/**
 * Outcome of a {@link MemoryService#remember} call.
 *
 * <p>{@link #alreadyExisted} is true when an active memory with the same
 * normalized text was already stored --- the caller is returned the
 * existing id rather than getting a duplicate inserted.
 */
public final class RememberResult {
    public final long    id;
    public final boolean alreadyExisted;

    public RememberResult(long id, boolean alreadyExisted) {
        this.id             = id;
        this.alreadyExisted = alreadyExisted;
    }
}
