package ai.ownsona;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Normalizes tags to a canonical vocabulary.
 *
 * <p>Tags go through three steps:
 * <ol>
 *   <li>Trim and lowercase ({@link Locale#ROOT}).</li>
 *   <li>Lookup in a synonym map; if present, replace with the canonical form.</li>
 *   <li>Otherwise pass through unchanged (in lowercase).</li>
 * </ol>
 *
 * <p>The synonym map is intentionally conservative: only obvious variants
 * of clearly-canonical terms.  Novel tags become canonical by adoption,
 * not by fiat --- if a brand-new tag passes through unchanged often
 * enough to feel established, add it (and its synonyms) here.
 *
 * <p>The duplicate-detection in {@link
 * ai.ownsona.memory.MemoryService#cleanTags} runs after normalization,
 * so synonyms that collapse to the same canonical (e.g. "tech" and
 * "Software") yield a single output tag.
 */
public final class TagNormalizer {

    /**
     * Synonym → canonical map.  Keys MUST be lowercase (they're compared
     * against the lowercased input).  Canonical forms are also lowercase.
     */
    private static final Map<String, String> SYNONYMS;
    static {
        final Map<String, String> m = new LinkedHashMap<>();

        // software
        m.put("tech",            "software");
        m.put("technology",      "software");
        m.put("programming",     "software");
        m.put("coding",          "software");
        m.put("code",            "software");
        m.put("dev",             "software");
        m.put("development",     "software");
        m.put("engineering",     "software");

        // publishing
        m.put("book",            "publishing");
        m.put("books",           "publishing");
        m.put("writing",         "publishing");
        m.put("author",          "publishing");
        m.put("authorship",      "publishing");

        // personal
        m.put("bio",             "personal");
        m.put("biography",       "personal");
        m.put("personal-info",   "personal");

        // family
        m.put("relatives",       "family");
        m.put("kin",             "family");

        // work
        m.put("job",             "work");
        m.put("career",          "work");
        m.put("employment",      "work");

        // preferences
        m.put("preference",      "preferences");
        m.put("pref",            "preferences");
        m.put("prefs",           "preferences");
        m.put("likes",           "preferences");
        m.put("dislikes",        "preferences");

        // health
        m.put("medical",         "health");
        m.put("medicine",        "health");
        m.put("medications",     "health");

        // philosophy
        m.put("religion",        "philosophy");
        m.put("beliefs",         "philosophy");
        m.put("values",          "philosophy");

        SYNONYMS = Collections.unmodifiableMap(m);
    }

    private TagNormalizer() {
    }

    /**
     * Normalize a single tag.  Returns null only if the input is null;
     * otherwise returns a trimmed-and-lowercased (and possibly remapped)
     * string, which may itself be empty if the input was blank.
     */
    public static String normalize(String tag) {
        if (tag == null)
            return null;
        final String key = tag.trim().toLowerCase(Locale.ROOT);
        if (key.isEmpty())
            return "";
        final String mapped = SYNONYMS.get(key);
        return (mapped != null) ? mapped : key;
    }

    /**
     * Normalize an array of tags, dropping null/empty values and removing
     * duplicates (post-normalization, so synonyms collapse).  Returns
     * insertion-order distinct entries.
     */
    public static String[] normalize(String[] tags) {
        if (tags == null)
            return new String[0];
        final Set<String> out = new LinkedHashSet<>();
        for (String t : tags) {
            final String n = normalize(t);
            if (n != null && !n.isEmpty())
                out.add(n);
        }
        return out.toArray(new String[0]);
    }

    /**
     * Returns true if {@code tag} is a known canonical (it's a value in
     * the synonym map, OR not present as a key).  Used by tests; not
     * intended for runtime decision-making.
     */
    static boolean isCanonical(String tag) {
        if (tag == null)
            return false;
        final String key = tag.trim().toLowerCase(Locale.ROOT);
        return !SYNONYMS.containsKey(key);
    }
}
