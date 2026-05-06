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
}
