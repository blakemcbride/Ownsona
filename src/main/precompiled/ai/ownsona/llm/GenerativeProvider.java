package ai.ownsona.llm;

/**
 * Pluggable generative-LLM source, used for reasoning tasks the server
 * performs on its own behalf --- currently the consolidation ("sleep")
 * job (Tier 3), and available for future LLM-assisted dedup / conflict
 * resolution.
 *
 * <p>This is a SECOND vendor seam, deliberately separate from
 * {@link ai.ownsona.embeddings.EmbeddingProvider}: embeddings and the
 * reasoning model are configured independently (their own keys, model,
 * and endpoint), so the operator can point them at different vendors.
 *
 * <p>It is always OPTIONAL.  When no generative provider is configured
 * (no {@code LLM_API_KEY}), the server falls back to its pure
 * embedding-and-heuristics behavior and the features that need this seam
 * simply stay off.  Generative calls must never sit in the synchronous
 * recall/remember hot path in a way that would make a write fail when the
 * model is down --- the seam is for background/maintenance work and
 * best-effort assists.
 */
public interface GenerativeProvider {

    /**
     * Run a single instruction-style completion: given a system
     * instruction and a user message, return the model's text reply.
     * The reply is plain text; callers that expect JSON parse it
     * themselves (and must tolerate prose/markdown wrapping).
     */
    String complete(String systemPrompt, String userPrompt) throws Exception;

    /** Model identifier, for logging / provenance. */
    String modelName();
}
