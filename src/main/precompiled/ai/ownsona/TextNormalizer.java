package ai.ownsona;

/**
 * Light text normalization for stored memories.
 *
 * <p>{@link #clean} is for the user-visible {@code text} column: it only
 * strips leading and trailing whitespace, preserving the user's wording.
 *
 * <p>{@link #normalize} produces the {@code normalized_text} column used as
 * a duplicate-detection key: lower-cased and with internal whitespace
 * collapsed.  It is never shown to the user.
 */
public final class TextNormalizer {

    public static String clean(String text) {
        if (text == null)
            return null;
        return text.trim();
    }

    public static String normalize(String text) {
        if (text == null)
            return null;
        return text.trim()
                .replaceAll("\\s+", " ")
                .toLowerCase();
    }

    private TextNormalizer() {
    }
}
