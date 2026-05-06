package ai.ownsona;

import ai.ownsona.memory.PromptFormatter;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PromptFormatterTest {

    @Test
    void noFactsBranchMatchesSpecExactly() {
        final String out = PromptFormatter.build("Where does my son work?", Collections.emptyList());
        final String expected =
                "The following are previously known facts:\n\n" +
                "No relevant previously known facts were found.\n\n" +
                "-----------------\n\n" +
                "The following is the user's prompt:\n\n" +
                "Where does my son work?";
        assertEquals(expected, out);
    }

    @Test
    void singleFactBranchMatchesSpecExactly() {
        final String out = PromptFormatter.build(
                "Where does my son work?",
                Collections.singletonList("My son Colby works for Dropbox."));
        final String expected =
                "The following are previously known facts:\n\n" +
                "My son Colby works for Dropbox.\n\n" +
                "-----------------\n\n" +
                "The following is the user's prompt:\n\n" +
                "Where does my son work?";
        assertEquals(expected, out);
    }

    @Test
    void multipleFactsAreSeparatedByBlankLines() {
        final List<String> facts = Arrays.asList(
                "My son Colby lives in Los Angeles.",
                "My son Colby works for Dropbox.");
        final String out = PromptFormatter.build("What about my son?", facts);
        final String expected =
                "The following are previously known facts:\n\n" +
                "My son Colby lives in Los Angeles.\n\n" +
                "My son Colby works for Dropbox.\n\n" +
                "-----------------\n\n" +
                "The following is the user's prompt:\n\n" +
                "What about my son?";
        assertEquals(expected, out);
    }

    @Test
    void nullPromptCoercesToEmpty() {
        final String out = PromptFormatter.build(null, Collections.emptyList());
        assertEquals(
                "The following are previously known facts:\n\n" +
                "No relevant previously known facts were found.\n\n" +
                "-----------------\n\n" +
                "The following is the user's prompt:\n\n",
                out);
    }

    @Test
    void nullFactsListTreatedAsEmpty() {
        final String out = PromptFormatter.build("Q", null);
        assertEquals(
                "The following are previously known facts:\n\n" +
                "No relevant previously known facts were found.\n\n" +
                "-----------------\n\n" +
                "The following is the user's prompt:\n\n" +
                "Q",
                out);
    }
}
