package ai.ownsona;

import java.util.regex.Pattern;

/**
 * Reject obvious credentials before storing them as memories.
 *
 * <p>This is a best-effort filter, not a security boundary.  False negatives
 * are accepted; aggressive false-positive heuristics (high-entropy strings,
 * long Base64) are deliberately omitted because the user may legitimately
 * want to remember opaque identifiers.
 */
public final class SecretScanner {

    private static final Pattern[] PATTERNS = {
        Pattern.compile("\\bsk-proj-[A-Za-z0-9_-]{16,}"),
        Pattern.compile("\\bsk-ant-[A-Za-z0-9_-]{16,}"),
        Pattern.compile("\\bsk-[A-Za-z0-9_-]{20,}"),
        Pattern.compile("\\bghp_[A-Za-z0-9]{30,}"),
        Pattern.compile("\\bghs_[A-Za-z0-9]{30,}"),
        Pattern.compile("\\bgithub_pat_[A-Za-z0-9_]{60,}"),
        Pattern.compile("\\bAKIA[0-9A-Z]{16}\\b"),
        Pattern.compile("\\bASIA[0-9A-Z]{16}\\b"),
        Pattern.compile("\\bxox[baprs]-[A-Za-z0-9-]{10,}"),
        Pattern.compile("AIza[0-9A-Za-z_-]{30,}"),
        Pattern.compile("\\beyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}"),
        Pattern.compile("-----BEGIN [A-Z ]*PRIVATE KEY-----"),
    };

    /**
     * @return null if the text appears clean, or a short user-facing error message
     *         describing why it was rejected.
     */
    public static String detect(String text) {
        if (text == null)
            return null;
        for (Pattern p : PATTERNS) {
            if (p.matcher(text).find())
                return "Refusing to store apparent credential or secret. Remove the sensitive value and resubmit.";
        }
        return null;
    }

    private SecretScanner() {
    }
}
