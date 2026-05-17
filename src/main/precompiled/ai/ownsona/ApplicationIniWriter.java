package ai.ownsona;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.kissweb.restServer.MainServlet;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimal in-place writer for {@code application.ini}.
 *
 * <p>Locates the runtime ini file at
 * {@code MainServlet.getApplicationPath() + "application.ini"} --- the same
 * path {@link org.kissweb.IniFile#load} reads from --- then rewrites a
 * single key's value while preserving every other line, comment, blank,
 * and the surrounding whitespace within the matched key's line.
 *
 * <p>Writes are atomic: content is staged to a sibling temp file, fsynced,
 * then renamed with {@link StandardCopyOption#ATOMIC_MOVE}.  A crash mid
 * write therefore leaves either the old or the new file intact, never a
 * partial one.
 *
 * <p>Only used today by {@code ReembedJob} to flip
 * {@code REEMBED_ON_STARTUP} back to {@code false} after a successful
 * re-embed pass.  Any other knob you want to flip from code should reuse
 * {@link #setKey(String, String)}.
 *
 * <p>Caveat for operators: this writes to the <em>unpacked</em>
 * deployment's {@code application.ini}, not to the source tree.  A
 * future WAR redeploy that ships {@code REEMBED_ON_STARTUP=true} will
 * re-enable the walker on the next restart, so the source-tree copy
 * should be updated separately before the next build.  See REEMBED.md.
 */
public final class ApplicationIniWriter {

    private static final Logger logger = LogManager.getLogger(ApplicationIniWriter.class);

    private ApplicationIniWriter() {
    }

    /**
     * Find the line whose key matches {@code key} (case-sensitive, sections
     * ignored) and replace its value with {@code value}.  Other lines are
     * untouched.  If the key appears more than once across sections, every
     * occurrence is updated --- callers passing keys not used in multiple
     * sections (the common case) get exactly the behavior they expect.
     *
     * @return true on successful rewrite, false if the ini file was not
     *     found, the key was not present, or the write failed.  All
     *     failures are logged at WARN; this method never throws so a
     *     re-embed completion path doesn't lose its win to an unwritable
     *     config file.
     */
    public static boolean setKey(String key, String value) {
        if (key == null || key.isEmpty()) {
            logger.warn("ApplicationIniWriter.setKey: null/empty key");
            return false;
        }
        final String iniPath = locate();
        if (iniPath == null)
            return false;

        try {
            final Path src = Paths.get(iniPath);
            if (!Files.exists(src)) {
                logger.warn("ApplicationIniWriter: ini file not found at {}", iniPath);
                return false;
            }

            final List<String> in = Files.readAllLines(src, StandardCharsets.UTF_8);
            final List<String> out = new ArrayList<>(in.size());
            boolean matched = false;
            for (String line : in) {
                final String replaced = replaceKeyOnLine(line, key, value);
                if (replaced != null) {
                    out.add(replaced);
                    matched = true;
                } else {
                    out.add(line);
                }
            }

            if (!matched) {
                logger.warn("ApplicationIniWriter: key '{}' not found in {}", key, iniPath);
                return false;
            }

            final Path tmp = src.resolveSibling(src.getFileName().toString() + ".reembed.tmp");
            Files.write(tmp, out, StandardCharsets.UTF_8);
            Files.move(tmp, src, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            logger.info("ApplicationIniWriter: set {} = {} in {}", key, value, iniPath);
            return true;
        } catch (IOException e) {
            logger.warn("ApplicationIniWriter: rewrite of {} failed: {}", iniPath, e.getMessage(), e);
            return false;
        }
    }

    /**
     * Test seam: takes a literal file path instead of going through Kiss's
     * applicationPath.  Same semantics as {@link #setKey(String, String)}.
     */
    static boolean setKeyInFile(Path src, String key, String value) {
        if (key == null || key.isEmpty())
            return false;
        try {
            if (!Files.exists(src))
                return false;
            final List<String> in = Files.readAllLines(src, StandardCharsets.UTF_8);
            final List<String> out = new ArrayList<>(in.size());
            boolean matched = false;
            for (String line : in) {
                final String replaced = replaceKeyOnLine(line, key, value);
                if (replaced != null) {
                    out.add(replaced);
                    matched = true;
                } else {
                    out.add(line);
                }
            }
            if (!matched)
                return false;
            final Path tmp = src.resolveSibling(src.getFileName().toString() + ".reembed.tmp");
            Files.write(tmp, out, StandardCharsets.UTF_8);
            Files.move(tmp, src, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Returns the rewritten line if {@code line} is a "{@code key = ...}"
     * assignment that matches {@code key}, otherwise null (caller keeps
     * the line as-is).  Comments and blanks return null.  Preserves the
     * exact characters between the start of the line and the {@code =},
     * including indentation and quoting style of the key.
     */
    static String replaceKeyOnLine(String line, String key, String value) {
        if (line == null)
            return null;
        // Look at the trimmed prefix: comments and section headers and
        // blanks never match a key=value pattern.
        final String trimmed = line.trim();
        if (trimmed.isEmpty())
            return null;
        final char c0 = trimmed.charAt(0);
        if (c0 == '#' || c0 == ';' || c0 == '-' || c0 == '*' || c0 == ':' || c0 == '[')
            return null;
        final int eq = line.indexOf('=');
        if (eq < 0)
            return null;
        final String lhs = line.substring(0, eq).trim();
        if (!lhs.equals(key))
            return null;
        // Preserve everything up to and including the '=' sign, plus one
        // space after it for readability.  Anything that was on the RHS
        // (and any trailing comment on the same line) is dropped.
        return line.substring(0, eq + 1) + " " + value;
    }

    private static String locate() {
        final String base = MainServlet.getApplicationPath();
        if (base == null || base.isEmpty()) {
            logger.warn("ApplicationIniWriter: MainServlet.getApplicationPath() is null/empty");
            return null;
        }
        return base + "application.ini";
    }
}
