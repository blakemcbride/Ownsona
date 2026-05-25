package ai.ownsona.memory;

import java.util.Date;

/**
 * Optional cleanup-style filters applied by {@link MemoryRepository#listRecent}
 * and {@link MemoryRepository#count}.
 *
 * <p>All fields are nullable / falsy = "don't filter on this column."  Pass
 * {@code null} to either repo method for "no extra filters" --- the existing
 * call sites and the recall / similarity paths continue to work unchanged.
 *
 * <p>Public mutable fields by convention with the other transport objects in
 * this package; not a domain entity with invariants.
 */
public final class MemoryFilter {
    /** When true, restrict to rows with no tags (empty tag array). */
    public boolean untaggedOnly;

    /** When non-null, restrict to rows whose text length is at least this many characters. */
    public Integer minChars;

    /** When non-null, restrict to rows whose text length is at most this many characters. */
    public Integer maxChars;

    /**
     * When non-null, restrict to rows that have NOT been confirmed since
     * this instant --- a row qualifies if {@code last_confirmed_at IS NULL}
     * (never confirmed) OR {@code last_confirmed_at < this}.  Useful for
     * finding stale memories during cleanup.
     */
    public Date notConfirmedSince;

    public boolean isEmpty() {
        return !untaggedOnly && minChars == null && maxChars == null && notConfirmedSince == null;
    }
}
