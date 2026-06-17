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

    // Optional freshness signals.  Both nullable: null = no expiration set
    // / never confirmed (the typical durable-memory case).
    public java.util.Date expiresAt;
    public java.util.Date lastConfirmedAt;

    // Protection flag.  New rows start Unspecified; the column has a SQL
    // default of 'U' as a safety net.  See MemoryRow.keep.
    public String   keep = "U";

    // Learned salience (Tier 1).  New rows seed salience from importance
    // so a just-stored fact starts at the same weight the old importance-
    // only ranking gave it.  use_count / reward_sum / last_used_at start
    // empty (column defaults: 0 / 0.0 / NULL).
    public Double   salience;
}
