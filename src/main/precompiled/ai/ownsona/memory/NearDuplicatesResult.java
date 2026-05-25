package ai.ownsona.memory;

import java.util.Collections;
import java.util.List;

/**
 * Outcome of a {@link MemoryService#findNearDuplicates} call.
 *
 * <p>{@link #groups} are sorted strongest-cluster-first by
 * {@link NearDuplicateGroup#maxSimilarity} and capped to the request's
 * limit.  {@link #pairsAboveThreshold} is the raw count of qualifying
 * pairs returned by the SQL query (post-canonicalization, before
 * clustering) --- useful when comparing thresholds.
 */
public final class NearDuplicatesResult {
    public final List<NearDuplicateGroup> groups;
    public final double                    threshold;
    public final int                       pairsAboveThreshold;

    public NearDuplicatesResult(List<NearDuplicateGroup> groups, double threshold, int pairsAboveThreshold) {
        this.groups              = (groups == null) ? Collections.emptyList() : groups;
        this.threshold           = threshold;
        this.pairsAboveThreshold = pairsAboveThreshold;
    }
}
