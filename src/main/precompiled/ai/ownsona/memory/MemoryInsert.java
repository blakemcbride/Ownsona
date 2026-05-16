package ai.ownsona.memory;

/**
 * Parameter object for {@link MemoryRepository#insert}.
 */
public final class MemoryInsert {
    public String   userId;
    public String   text;
    public String   normalizedText;
    public float[]  embedding;
    public String[] tags;
    public double   importance = 0.5;
    public String   sourceProvider;
    public String   sourceClient;
    public String   sourceConversationId;
    public String   embeddingProvider;
    public String   embeddingModel;

    // Raw JSONB blob written to memories.metadata.  null → '{}' is written.
    public String   metadataJson;

    // Per-row data version (see RecordUpgraderRegistry.CURRENT_RECORD_VERSION).
    // Callers should set this to CURRENT_RECORD_VERSION so new rows start at
    // current; the column has a SQL default of 1 as a safety net.
    public int      recordVersion = 1;
}
