package ai.ownsona.memory;

/**
 * Plain transport object for one {@code (subject, predicate, object)} edge
 * in the relation graph (Tier 4 phase 2), plus the id and current text of
 * the memory it was extracted from.
 *
 * <p>Public fields, no invariants --- a transport object between the
 * repository and the service/MCP layer.
 */
public final class MemoryRelation {
    public long   id;
    public String subject;
    public String predicate;
    public String object;
    public long   sourceMemoryId;
    public String sourceText;   // current text of the source memory (joined at read time)
}
