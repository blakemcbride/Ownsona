package ai.ownsona;

import ai.ownsona.embeddings.MockEmbeddingProvider;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MockEmbeddingProviderTest {

    @Test
    void dimensionsMatchConstructor() throws Exception {
        final MockEmbeddingProvider p = new MockEmbeddingProvider(1536);
        final float[] v = p.embed("hello");
        assertEquals(1536, v.length);
        assertEquals(1536, p.dimensions());
    }

    @Test
    void deterministic() throws Exception {
        final MockEmbeddingProvider p = new MockEmbeddingProvider(64);
        final float[] a = p.embed("My son Colby lives in LA");
        final float[] b = p.embed("My son Colby lives in LA");
        for (int i = 0; i < a.length; i++)
            assertEquals(a[i], b[i], 1e-9, "differ at i=" + i);
    }

    @Test
    void differentTextDifferentVector() throws Exception {
        final MockEmbeddingProvider p = new MockEmbeddingProvider(64);
        final float[] a = p.embed("apple");
        final float[] b = p.embed("orange");
        boolean differs = false;
        for (int i = 0; i < a.length; i++) {
            if (Math.abs(a[i] - b[i]) > 1e-6) {
                differs = true;
                break;
            }
        }
        assertTrue(differs, "different inputs should produce different vectors");
    }

    @Test
    void unitNorm() throws Exception {
        final MockEmbeddingProvider p = new MockEmbeddingProvider(128);
        final float[] v = p.embed("anything");
        double sumSq = 0.0;
        for (float f : v)
            sumSq += f * f;
        final double norm = Math.sqrt(sumSq);
        assertEquals(1.0, norm, 1e-5, "expected unit-norm vector, got |v|=" + norm);
    }

    @Test
    void nonPositiveDimensionsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new MockEmbeddingProvider(0));
        assertThrows(IllegalArgumentException.class, () -> new MockEmbeddingProvider(-1));
    }

    @Test
    void modelName() {
        assertEquals("mock-sha256", new MockEmbeddingProvider(8).modelName());
    }
}
