package ai.ownsona;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class TextNormalizerTest {

    @Test
    void cleanOnlyTrimsLeadingAndTrailingWhitespace() {
        assertEquals("My son lives in LA.", TextNormalizer.clean("  My son lives in LA.  "));
        assertEquals("Multi  word  preserved", TextNormalizer.clean("Multi  word  preserved"));
        assertEquals("", TextNormalizer.clean("   "));
        assertNull(TextNormalizer.clean(null));
    }

    @Test
    void normalizeLowercasesAndCollapsesWhitespace() {
        assertEquals("my son colby lives in los angeles.",
                TextNormalizer.normalize("  My  son\tColby\nlives\nin Los Angeles.  "));
        assertEquals("a b c", TextNormalizer.normalize("A   B   C"));
    }

    @Test
    void normalizeIsIdempotent() {
        final String once = TextNormalizer.normalize("  Hello\t World ");
        final String twice = TextNormalizer.normalize(once);
        assertEquals(once, twice);
    }

    @Test
    void duplicateDetectionUseCases() {
        // Things that should be considered duplicates
        assertEquals(
                TextNormalizer.normalize("My son Colby lives in LA."),
                TextNormalizer.normalize("my son colby lives in la."));
        assertEquals(
                TextNormalizer.normalize("Hello world"),
                TextNormalizer.normalize("hello   world"));
    }
}
