package ai.ownsona;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link ApplicationIniWriter}.
 *
 * <p>Drive the {@link ApplicationIniWriter#setKeyInFile} test-seam (which
 * takes an explicit Path) rather than the production {@link ApplicationIniWriter#setKey}
 * entry point, so we don't need a live MainServlet / applicationPath.
 */
class ApplicationIniWriterTest {

    @Test
    void replaceKeyOnLine_simpleAssignment() {
        // Whatever was on the RHS gets replaced; key + '=' get preserved.
        assertEquals(
                "REEMBED_ON_STARTUP = false",
                ApplicationIniWriter.replaceKeyOnLine("REEMBED_ON_STARTUP = true", "REEMBED_ON_STARTUP", "false"));
    }

    @Test
    void replaceKeyOnLine_preservesIndentation() {
        assertEquals(
                "    REEMBED_ON_STARTUP = false",
                ApplicationIniWriter.replaceKeyOnLine("    REEMBED_ON_STARTUP = true", "REEMBED_ON_STARTUP", "false"));
    }

    @Test
    void replaceKeyOnLine_preservesSpacingOnLHS() {
        // Whatever whitespace is before the '=' stays put.
        assertEquals(
                "REEMBED_ON_STARTUP    = false",
                ApplicationIniWriter.replaceKeyOnLine("REEMBED_ON_STARTUP    = true", "REEMBED_ON_STARTUP", "false"));
    }

    @Test
    void replaceKeyOnLine_isCaseSensitiveOnKey() {
        assertNull(ApplicationIniWriter.replaceKeyOnLine("reembed_on_startup = true", "REEMBED_ON_STARTUP", "false"));
    }

    @Test
    void replaceKeyOnLine_ignoresComments() {
        assertNull(ApplicationIniWriter.replaceKeyOnLine("# REEMBED_ON_STARTUP = true", "REEMBED_ON_STARTUP", "false"));
        assertNull(ApplicationIniWriter.replaceKeyOnLine("; REEMBED_ON_STARTUP = true", "REEMBED_ON_STARTUP", "false"));
    }

    @Test
    void replaceKeyOnLine_ignoresSectionHeader() {
        assertNull(ApplicationIniWriter.replaceKeyOnLine("[main]", "main", "x"));
    }

    @Test
    void replaceKeyOnLine_ignoresBlankLine() {
        assertNull(ApplicationIniWriter.replaceKeyOnLine("", "REEMBED_ON_STARTUP", "false"));
        assertNull(ApplicationIniWriter.replaceKeyOnLine("   ", "REEMBED_ON_STARTUP", "false"));
    }

    @Test
    void replaceKeyOnLine_substringKeyDoesNotMatch() {
        // The key REEMBED must not match a line for REEMBED_ON_STARTUP.
        assertNull(ApplicationIniWriter.replaceKeyOnLine("REEMBED_ON_STARTUP = true", "REEMBED", "false"));
    }

    @Test
    void replaceKeyOnLine_dropsTrailingCommentOnRhs() {
        // We intentionally drop anything after '=' (and replace with the new value)
        // including a trailing same-line comment.  Documented behavior --- preserving
        // an inline comment after replacing a value is more code than it's worth and
        // application.ini doesn't lean on inline comments.
        assertEquals(
                "REEMBED_ON_STARTUP = false",
                ApplicationIniWriter.replaceKeyOnLine("REEMBED_ON_STARTUP = true   # was on", "REEMBED_ON_STARTUP", "false"));
    }

    @Test
    void setKeyInFile_rewritesAtomicallyAndPreservesEverythingElse(@TempDir Path tmp) throws Exception {
        final Path ini = tmp.resolve("application.ini");
        final String original =
                "# Header comment\n" +
                "\n" +
                "[main]\n" +
                "EMBEDDING_MODEL = text-embedding-3-small\n" +
                "\n" +
                "# toggled by ReembedJob\n" +
                "REEMBED_ON_STARTUP = true\n" +
                "\n" +
                "EMBEDDING_DIMENSIONS = 1536\n";
        Files.writeString(ini, original, StandardCharsets.UTF_8);

        final boolean ok = ApplicationIniWriter.setKeyInFile(ini, "REEMBED_ON_STARTUP", "false");
        assertTrue(ok);

        final List<String> after = Files.readAllLines(ini, StandardCharsets.UTF_8);
        assertEquals("# Header comment", after.get(0));
        assertEquals("", after.get(1));
        assertEquals("[main]", after.get(2));
        assertEquals("EMBEDDING_MODEL = text-embedding-3-small", after.get(3));
        assertEquals("", after.get(4));
        assertEquals("# toggled by ReembedJob", after.get(5));
        assertEquals("REEMBED_ON_STARTUP = false", after.get(6));
        assertEquals("", after.get(7));
        assertEquals("EMBEDDING_DIMENSIONS = 1536", after.get(8));
    }

    @Test
    void setKeyInFile_returnsFalseWhenKeyNotPresent(@TempDir Path tmp) throws Exception {
        final Path ini = tmp.resolve("application.ini");
        Files.writeString(ini, "[main]\nEMBEDDING_MODEL = text-embedding-3-small\n", StandardCharsets.UTF_8);

        final boolean ok = ApplicationIniWriter.setKeyInFile(ini, "REEMBED_ON_STARTUP", "false");
        assertFalse(ok);

        // File contents must be untouched on a no-match.
        assertEquals("[main]\nEMBEDDING_MODEL = text-embedding-3-small\n",
                Files.readString(ini, StandardCharsets.UTF_8));
    }

    @Test
    void setKeyInFile_returnsFalseWhenFileMissing(@TempDir Path tmp) {
        final Path ini = tmp.resolve("nope.ini");
        final boolean ok = ApplicationIniWriter.setKeyInFile(ini, "REEMBED_ON_STARTUP", "false");
        assertFalse(ok);
    }

    @Test
    void setKeyInFile_idempotentWhenAlreadyAtTargetValue(@TempDir Path tmp) throws Exception {
        final Path ini = tmp.resolve("application.ini");
        Files.writeString(ini, "REEMBED_ON_STARTUP = false\n", StandardCharsets.UTF_8);

        final boolean ok = ApplicationIniWriter.setKeyInFile(ini, "REEMBED_ON_STARTUP", "false");
        assertTrue(ok);
        assertEquals("REEMBED_ON_STARTUP = false\n",
                Files.readString(ini, StandardCharsets.UTF_8));
    }

    @Test
    void setKeyInFile_leavesNoTempFileOnSuccess(@TempDir Path tmp) throws Exception {
        final Path ini = tmp.resolve("application.ini");
        Files.writeString(ini, "REEMBED_ON_STARTUP = true\n", StandardCharsets.UTF_8);
        ApplicationIniWriter.setKeyInFile(ini, "REEMBED_ON_STARTUP", "false");

        try (var stream = Files.list(tmp)) {
            assertEquals(1L, stream.filter(p -> p.getFileName().toString().endsWith(".tmp")).count() == 0L ? 1L : 0L);
        }
        // Re-state: only "application.ini" should remain.
        try (var stream = Files.list(tmp)) {
            final long count = stream.count();
            assertEquals(1L, count);
        }
        // And it has the new value.
        assertNotNull(Files.readString(ini, StandardCharsets.UTF_8));
        assertTrue(Files.readString(ini, StandardCharsets.UTF_8).contains("REEMBED_ON_STARTUP = false"));
    }
}
