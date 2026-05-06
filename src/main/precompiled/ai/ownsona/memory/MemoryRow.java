package ai.ownsona.memory;

import java.util.Date;

/**
 * Plain holder for a memory row read out of the database.
 *
 * <p>Public fields are intentional: this is a transport object between the
 * repository and the service/MCP layer, not a domain entity with invariants.
 * {@link #score} is only populated by the recall path.
 */
public final class MemoryRow {
    public long     id;
    public String   text;
    public String[] tags;
    public double   importance;
    public String   sourceProvider;
    public String   embeddingProvider;
    public String   embeddingModel;
    public Date     createdAt;
    public Date     updatedAt;
    public Date     deletedAt;
    public double   score;
}
