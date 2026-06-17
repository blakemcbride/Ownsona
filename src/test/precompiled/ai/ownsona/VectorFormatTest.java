package ai.ownsona;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VectorFormatTest {

    @Test
    void toLiteralFormatsPgvectorBracketedList() {
        final float[] v = {0.1f, -0.5f, 1.0f};
        final String s = VectorFormat.toLiteral(v);
        assertTrue(s.startsWith("[") && s.endsWith("]"), "got: " + s);
        // Standard Java float toString is acceptable to pgvector --- canonical format
        assertEquals("[0.1,-0.5,1.0]", s);
    }

    @Test
    void toLiteralEmptyVector() {
        assertEquals("[]", VectorFormat.toLiteral(new float[0]));
    }

    @Test
    void toLiteralRejectsNull() {
        assertThrows(IllegalArgumentException.class, () -> VectorFormat.toLiteral(null));
    }

    @Test
    void toPgArrayLiteralEmpty() {
        assertEquals("{}", VectorFormat.toPgArrayLiteral(null));
        assertEquals("{}", VectorFormat.toPgArrayLiteral(new String[0]));
    }

    @Test
    void toPgArrayLiteralBasicTags() {
        assertEquals("{\"family\",\"work\"}",
                VectorFormat.toPgArrayLiteral(new String[]{"family", "work"}));
    }

    @Test
    void toPgArrayLiteralEscapesQuotesAndBackslashes() {
        // A tag containing a backslash and a double quote.
        final String[] tags = {"path\\with\"both"};
        // Expected: {"path\\with\"both"}  -- literal backslashes + escaped quote
        assertEquals("{\"path\\\\with\\\"both\"}", VectorFormat.toPgArrayLiteral(tags));
    }

    @Test
    void toPgArrayLiteralPreservesUnicode() {
        assertEquals("{\"café\",\"résumé\"}",
                VectorFormat.toPgArrayLiteral(new String[]{"café", "résumé"}));
    }

    // -------------------------------------------------------------------
    // parseLiteral --- read a pgvector text literal back into a float[]
    // -------------------------------------------------------------------

    @Test
    void parseLiteralRoundTrips() {
        final float[] v = {0.1f, -0.5f, 1.0f};
        assertArrayEquals(v, VectorFormat.parseLiteral(VectorFormat.toLiteral(v)), 1e-6f);
    }

    @Test
    void parseLiteralHandlesNullEmptyAndBlankVector() {
        assertNull(VectorFormat.parseLiteral(null));
        assertNull(VectorFormat.parseLiteral("   "));
        assertArrayEquals(new float[0], VectorFormat.parseLiteral("[]"), 1e-6f);
    }

    // -------------------------------------------------------------------
    // blend --- reward-weighted move of a centroid toward a new vector
    // -------------------------------------------------------------------

    @Test
    void blendNullBaseReturnsCopyOfToward() {
        final float[] toward = {1.0f, 2.0f};
        final float[] out = VectorFormat.blend(null, toward, 0.3);
        assertArrayEquals(toward, out, 1e-6f);
        // Must be a copy, not the same array (so later mutation can't alias).
        out[0] = 99f;
        assertEquals(1.0f, toward[0], 1e-6f);
    }

    @Test
    void blendMovesBaseTowardTargetByWeight() {
        // (1-0.25)*0 + 0.25*4 = 1.0 ; (1-0.25)*8 + 0.25*0 = 6.0
        final float[] out = VectorFormat.blend(new float[]{0f, 8f}, new float[]{4f, 0f}, 0.25);
        assertArrayEquals(new float[]{1.0f, 6.0f}, out, 1e-6f);
    }

    @Test
    void blendRejectsLengthMismatchAndNullToward() {
        assertThrows(IllegalArgumentException.class,
                () -> VectorFormat.blend(new float[]{1f}, new float[]{1f, 2f}, 0.5));
        assertThrows(IllegalArgumentException.class,
                () -> VectorFormat.blend(new float[]{1f}, null, 0.5));
    }
}
