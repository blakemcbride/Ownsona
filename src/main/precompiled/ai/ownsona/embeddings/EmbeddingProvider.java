package ai.ownsona.embeddings;

import java.util.ArrayList;
import java.util.List;

/**
 * Pluggable text-to-vector embedding source.
 *
 * <p>The MCP server uses one provider at a time.  The abstraction exists so
 * that an alternative provider (Ollama, a local model, a different cloud
 * API) can be swapped in later without touching the repository or service
 * layers.
 */
public interface EmbeddingProvider {

    /**
     * Embed a single piece of text.
     *
     * @return a vector of length {@link #dimensions()}
     */
    float[] embed(String text) throws Exception;

    /**
     * Embed a list of texts and return a list of vectors in the same order.
     *
     * <p>The default implementation falls back to one {@link #embed(String)}
     * call per input.  Real providers should override this to amortize the
     * per-call HTTP overhead by sending all inputs in a single request ---
     * the speedup for a 100-item bulk import is roughly two orders of
     * magnitude.
     */
    default List<float[]> embedBatch(List<String> texts) throws Exception {
        final List<float[]> out = new ArrayList<>(texts.size());
        for (String t : texts)
            out.add(embed(t));
        return out;
    }

    /** Model identifier as it should be recorded with each memory row. */
    String modelName();

    /** Vector length produced by this provider; must match the {@code vector(N)} column. */
    int dimensions();
}
