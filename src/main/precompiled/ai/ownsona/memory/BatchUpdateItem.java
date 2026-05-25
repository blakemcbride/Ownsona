package ai.ownsona.memory;

/**
 * One item in a {@link MemoryService#updateBatch} call.  Public mutable
 * fields because this is a transport object between the MCP layer and the
 * service.
 *
 * <p>Only {@link #id} is required; every other field follows
 * "null = leave the column unchanged" semantics.  At least one of
 * {@code text}, {@code tags}, {@code importance}, {@code expiresAt},
 * {@code lastConfirmedAt} must be non-null --- a row with id but no
 * changes is rejected as {@code INVALID_INPUT}.
 */
public final class BatchUpdateItem {
    public Long              id;
    public String            text;
    public String[]          tags;
    public Double            importance;
    public java.util.Date    expiresAt;
    public java.util.Date    lastConfirmedAt;
}
