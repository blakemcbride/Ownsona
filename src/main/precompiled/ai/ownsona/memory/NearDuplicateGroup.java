package ai.ownsona.memory;

import java.util.Collections;
import java.util.List;

/**
 * One cluster of near-duplicate memories returned by
 * {@link MemoryService#findNearDuplicates}.
 *
 * <p>Clusters are formed by union-find over pairs whose cosine similarity
 * meets the request's threshold --- so transitively similar rows (a~b,
 * b~c) appear in the same group even if a and c don't directly meet the
 * threshold.  {@link #maxSimilarity} is the strongest pair-similarity
 * observed within this cluster.
 */
public final class NearDuplicateGroup {
    public final List<MemoryRow> memories;       // sorted by id ascending
    public final double          maxSimilarity;  // strongest pair within the cluster
    public final int             pairCount;      // pairs above threshold inside the cluster

    public NearDuplicateGroup(List<MemoryRow> memories, double maxSimilarity, int pairCount) {
        this.memories      = (memories == null) ? Collections.emptyList() : memories;
        this.maxSimilarity = maxSimilarity;
        this.pairCount     = pairCount;
    }
}
