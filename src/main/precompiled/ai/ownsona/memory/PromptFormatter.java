package ai.ownsona.memory;

import java.util.List;

/**
 * Builds the augmented-prompt envelope returned by the MCP
 * {@code build_context_prompt} tool.
 *
 * <p>The format is dictated verbatim by OWNSONA_SPEC.md section 18 ---
 * including the literal "-----------------" divider and the empty-fact
 * fallback line.  Kept here as a pure function (no DB, no embedder) so the
 * exact byte-for-byte format can be unit-tested cheaply.
 */
public final class PromptFormatter {

    private static final String NO_FACTS_SENTINEL = "No relevant previously known facts were found.";

    public static String build(String userPrompt, List<String> facts) {
        if (userPrompt == null)
            userPrompt = "";

        final StringBuilder sb = new StringBuilder();
        sb.append("The following are previously known facts:\n\n");
        if (facts == null || facts.isEmpty()) {
            sb.append(NO_FACTS_SENTINEL).append("\n\n");
        } else {
            for (String f : facts) {
                if (f != null)
                    sb.append(f).append("\n\n");
            }
        }
        sb.append("-----------------\n\n");
        sb.append("The following is the user's prompt:\n\n");
        sb.append(userPrompt);
        return sb.toString();
    }

    private PromptFormatter() {
    }
}
