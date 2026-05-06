package ai.ownsona;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
