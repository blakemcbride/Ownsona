package ai.ownsona;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the tag-vocabulary normalizer added in Phase 1B.
 */
class TagNormalizerTest {

    // -------------------------------------------------------------------
    // single-tag normalize()
    // -------------------------------------------------------------------

    @Test
    void normalizeNullReturnsNull() {
        assertNull(TagNormalizer.normalize((String) null));
    }

    @Test
    void normalizeEmptyOrWhitespaceReturnsEmpty() {
        assertEquals("", TagNormalizer.normalize(""));
        assertEquals("", TagNormalizer.normalize("   "));
    }

    @Test
    void normalizeLowercasesUnknownTag() {
        // "philately" isn't in the synonym map; passes through lowercased.
        assertEquals("philately", TagNormalizer.normalize("Philately"));
        assertEquals("philately", TagNormalizer.normalize("PHILATELY"));
        assertEquals("philately", TagNormalizer.normalize("  philately  "));
    }

    @Test
    void normalizeMapsSynonymsToCanonical() {
        assertEquals("software", TagNormalizer.normalize("tech"));
        assertEquals("software", TagNormalizer.normalize("Programming"));
        assertEquals("software", TagNormalizer.normalize("CODING"));
        assertEquals("software", TagNormalizer.normalize("  dev  "));
        assertEquals("publishing", TagNormalizer.normalize("books"));
        assertEquals("personal",   TagNormalizer.normalize("bio"));
        assertEquals("family",     TagNormalizer.normalize("relatives"));
        assertEquals("work",       TagNormalizer.normalize("job"));
        assertEquals("preferences",TagNormalizer.normalize("pref"));
        assertEquals("health",     TagNormalizer.normalize("medical"));
        assertEquals("philosophy", TagNormalizer.normalize("beliefs"));
    }

    @Test
    void normalizeIsIdempotent() {
        // Applying twice yields the same result --- a canonical stays
        // canonical, a synonym maps once and then sticks.
        for (String t : new String[]{"software", "tech", "FAMILY", "philately", ""}) {
            final String once  = TagNormalizer.normalize(t);
            final String twice = TagNormalizer.normalize(once);
            assertEquals(once, twice, "idempotency violated for: '" + t + "'");
        }
    }

    @Test
    void canonicalAlreadyInVocabularyPassesThrough() {
        // These are values in the synonym map (canonical destinations);
        // they should pass through unchanged.
        for (String c : new String[]{"software", "family", "work",
                                     "preferences", "health", "philosophy",
                                     "publishing", "personal"}) {
            assertEquals(c, TagNormalizer.normalize(c));
            assertTrue(TagNormalizer.isCanonical(c), c + " should be canonical");
        }
    }

    // -------------------------------------------------------------------
    // array normalize()
    // -------------------------------------------------------------------

    @Test
    void normalizeArrayNullYieldsEmptyArray() {
        assertArrayEquals(new String[0], TagNormalizer.normalize((String[]) null));
    }

    @Test
    void normalizeArrayDropsNullsAndEmpties() {
        final String[] in  = new String[]{"family", null, "", "  ", "work"};
        final String[] out = TagNormalizer.normalize(in);
        assertArrayEquals(new String[]{"family", "work"}, out);
    }

    @Test
    void normalizeArrayDedupsAfterMapping() {
        // tech → software, TECH → software, software → software --- one tag.
        final String[] in  = new String[]{"tech", "TECH", "software"};
        final String[] out = TagNormalizer.normalize(in);
        assertArrayEquals(new String[]{"software"}, out);
    }

    @Test
    void normalizeArrayPreservesInsertionOrder() {
        final String[] in  = new String[]{"books", "tech", "family"};
        final String[] out = TagNormalizer.normalize(in);
        // books → publishing first, tech → software second, family stays.
        assertArrayEquals(new String[]{"publishing", "software", "family"}, out);
    }

    @Test
    void normalizeArrayMixedKnownAndUnknown() {
        final String[] in  = new String[]{"tech", "Philately", "JOB"};
        final String[] out = TagNormalizer.normalize(in);
        assertArrayEquals(new String[]{"software", "philately", "work"}, out);
    }
}
