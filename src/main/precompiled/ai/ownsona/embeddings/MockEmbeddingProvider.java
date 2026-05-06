package ai.ownsona.embeddings;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Deterministic mock embedding provider for tests and offline smoke checks.
 *
 * <p>Derives a vector from {@code SHA-256(text)} repeated until it fills the
 * configured dimension count.  Two equal strings produce identical vectors;
 * different strings produce vectors that are uncorrelated under cosine
 * similarity, which is enough to exercise the recall ranking path without
 * calling out to OpenAI.
 *
 * <p>The vector is L2-normalized so that cosine and dot-product similarity
 * agree, matching the math pgvector uses.
 */
public final class MockEmbeddingProvider implements EmbeddingProvider {

    private static final String MODEL_NAME = "mock-sha256";

    private final int dimensions;

    public MockEmbeddingProvider(int dimensions) {
        if (dimensions <= 0)
            throw new IllegalArgumentException("dimensions must be positive");
        this.dimensions = dimensions;
    }

    @Override
    public float[] embed(String text) {
        final MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
        final byte[] base = md.digest(text == null ? new byte[0] : text.getBytes(StandardCharsets.UTF_8));

        final float[] out = new float[dimensions];
        double sumSq = 0.0;
        for (int i = 0; i < dimensions; i++) {
            // signed byte from the digest, biased to roughly [-1, 1]
            final byte b = base[i % base.length];
            final float f = (float) (b / 128.0);
            out[i] = f;
            sumSq += f * f;
        }
        final double norm = Math.sqrt(sumSq);
        if (norm > 0) {
            for (int i = 0; i < dimensions; i++)
                out[i] = (float) (out[i] / norm);
        }
        return out;
    }

    @Override
    public String modelName() {
        return MODEL_NAME;
    }

    @Override
    public int dimensions() {
        return dimensions;
    }
}
