package ai.ownsona.memory;

/**
 * One item in a {@link MemoryService#rememberBatch} call.  Public mutable
 * fields because this is a transport object between the MCP layer and the
 * service.
 */
public final class BatchRememberItem {
    public String   text;
    public String[] tags;
    public String   sourceProvider;
    public Double   importance;
}
