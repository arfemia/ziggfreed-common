package com.ziggfreed.common;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * A {@code .Text} property is written through {@link com.ziggfreed.common.ui.UiText}, never by a
 * bare {@code cmd.set}. This test is the enforcement half of that rule.
 *
 * <p><b>Why it is worth a build failure.</b> On the client a {@code .Text} property is a CLR
 * {@code System.String}, and the value a set command carries is deserialized straight into it. Every
 * {@code Message} goes over the wire as a structured document, and only the translation form
 * ({@code {"MessageId": ...}}) is one the client can turn back into a string. A raw or composed
 * message ({@code {"RawText": ...}}, {@code {"Children": [...]}}) makes it try to build a
 * {@code System.String} out of an object; it throws
 * {@code "No parameterless constructor defined for type 'System.String'"} and DISCONNECTS the
 * player, which on a singleplayer world ends the session. So the cost of getting this wrong is not a
 * blank label, it is the world going down - and it is reachable from CONTENT: an authored line whose
 * translation key nothing ships falls back to raw text, and the fallback is what kills the client.
 *
 * <p>Telling a {@code Message} from a {@link String} by looking at a call site is not something a
 * test can do - the value is usually a method call, and its return type is exactly what a reader
 * cannot see. So the rule is structural instead: nothing writes {@code .Text} directly, and
 * {@code UiText} (which carries an overload for each) decides. That is a rule this scan CAN hold,
 * and it stays true no matter what a future accessor returns.
 *
 * <p><b>What is scanned</b>: every {@code .java} file under every module's {@code src/main/java},
 * discovered by walking rather than by naming files, so a new module is covered the day it appears.
 * Comments are blanked first (prose is free to say whatever it needs to); string literals are NOT,
 * because the selector this looks for lives inside one.
 *
 * <p><b>What fails</b>: a {@code .set(...)} call whose argument list mentions a selector ending in
 * {@code .Text}. {@code .TextSpans} is untouched by this rule and does not match it (it takes a full
 * message tree and has none of the limits above).
 *
 * <p><b>Escape hatch</b>: {@code // TEXT-SINK-OK: <reason>} with a NON-EMPTY reason, on the offending
 * line or in the {@code //} comment block immediately above it. A bare marker fails just as the
 * unmarked line would. There is no good reason to reach for it yet; the first move is to call
 * {@code UiText.setText}, which does the right thing for both value kinds.
 */
class TextSinkGoesThroughUiTextTest {

    /** Where the library's modules keep their main sources, relative to the build's working dir. */
    private static final Path LIBRARY_ROOT = Path.of(".");

    /** A {@code .set(} call carrying a selector that ends in {@code .Text}. */
    private static final Pattern RAW_TEXT_SET =
            Pattern.compile("\\.set\\s*\\([^;]*\\.Text\"");

    /** The one class allowed to write the property, plus the test that proves it does. */
    private static final Pattern EXEMPT_FILE = Pattern.compile("UiText(Test)?\\.java$");

    /** The escape hatch. The reason after the colon must be non-empty. */
    private static final Pattern ALLOW_MARKER = Pattern.compile("//\\s*TEXT-SINK-OK:\\s*(\\S.*)?$");

    @Test
    void everyTextPropertyIsWrittenThroughUiText() throws IOException {
        List<Path> sources = librarySources();
        assertFalse(sources.isEmpty(), "no library sources found under " + LIBRARY_ROOT.toAbsolutePath());

        List<String> hits = new ArrayList<>();
        for (Path source : sources) {
            scan(source, hits);
        }

        assertTrue(hits.isEmpty(), () -> "A .Text property is written by a bare set (" + hits.size()
                + " hit(s)). Call UiText.setText(cmd, selector, value) instead - a Message that is not"
                + " a translation crashes the client on a .Text sink. If a call genuinely cannot go"
                + " through it, mark the line // TEXT-SINK-OK: <reason>.\n" + String.join("\n", hits));
    }

    /** Every module's main sources, in a stable order so failures read the same twice. */
    private static List<Path> librarySources() throws IOException {
        try (Stream<Path> walk = Files.walk(LIBRARY_ROOT)) {
            return walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".java"))
                    .filter(TextSinkGoesThroughUiTextTest::isMainSource)
                    .filter(p -> !EXEMPT_FILE.matcher(p.toString().replace('\\', '/')).find())
                    .sorted()
                    .toList();
        }
    }

    /** Main sources only: a test may hand-build a command to prove what the rule prevents. */
    private static boolean isMainSource(Path path) {
        String normalized = path.toString().replace('\\', '/');
        return normalized.contains("/src/main/java/") && !normalized.contains("/build/");
    }

    private static void scan(Path source, List<String> hits) throws IOException {
        List<String> raw = Files.readAllLines(source, StandardCharsets.UTF_8);
        List<String> code = stripComments(raw);
        for (int i = 0; i < code.size(); i++) {
            if (!RAW_TEXT_SET.matcher(code.get(i)).find()) {
                continue;
            }
            if (isAllowed(raw, i - 1, i)) {
                continue;
            }
            hits.add(source + ":" + (i + 1) + "  " + raw.get(i).trim());
        }
    }

    /**
     * Blank out comments so prose describing the rule never trips it. String literals are LEFT
     * ALONE: the selector being looked for is a string, so stripping them would blind the scan.
     */
    private static List<String> stripComments(List<String> raw) {
        List<String> out = new ArrayList<>(raw.size());
        boolean inBlock = false;
        for (String line : raw) {
            StringBuilder sb = new StringBuilder();
            boolean inString = false;
            for (int i = 0; i < line.length(); i++) {
                char c = line.charAt(i);
                char next = i + 1 < line.length() ? line.charAt(i + 1) : '\0';
                if (inBlock) {
                    if (c == '*' && next == '/') {
                        inBlock = false;
                        i++;
                    }
                    continue;
                }
                if (!inString && c == '/' && next == '*') {
                    inBlock = true;
                    i++;
                    continue;
                }
                if (!inString && c == '/' && next == '/') {
                    break;
                }
                if (c == '"' && (i == 0 || line.charAt(i - 1) != '\\')) {
                    inString = !inString;
                }
                sb.append(c);
            }
            out.add(sb.toString());
        }
        return out;
    }

    /** The marker on the offending line, or in the {@code //} block sitting immediately above it. */
    private static boolean isAllowed(List<String> raw, int aboveIndex, int lineIndex) {
        if (ALLOW_MARKER.matcher(raw.get(lineIndex)).find()) {
            return true;
        }
        for (int i = aboveIndex; i >= 0; i--) {
            String above = raw.get(i).trim();
            if (!above.startsWith("//")) {
                return false;
            }
            if (ALLOW_MARKER.matcher(above).find()) {
                return true;
            }
        }
        return false;
    }
}
