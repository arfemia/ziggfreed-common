package com.ziggfreed.common;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * The wiring root is REGISTRATION ONLY. This test is the enforcement half of that rule.
 *
 * <p><b>Why the root is special.</b> It is the one place in the library where an edge between any
 * two modules is legal, so it silently absorbs every awkward dependency that does not fit the
 * module graph. That makes it the natural drain for logic too - and logic that lands here is logic
 * no module can be tested, reasoned about, or reused without standing up the whole plugin. Keeping
 * the root to store registration, event wiring and registry population is what keeps the module
 * graph honest: if the root has to DECIDE something, that decision belongs in a module behind a
 * seam the root merely fills.
 *
 * <p><b>What is scanned</b>: {@link ZiggfreedCommonPlugin} and
 * {@code asset/FrameworkAssetRegistrar}, the root's whole source. Comments, javadoc and string
 * literals are stripped first, so prose and log lines are free to say whatever they need to.
 *
 * <p><b>What fails</b>: a loop ({@code for} / {@code while} / {@code do}), a {@code switch}, or an
 * {@code else} branch - each of them a decision rather than a registration. A {@code try}/
 * {@code catch} is fine (the root guards every engine call). An {@code if} is fine when it reads as
 * a GUARD: its condition mentions {@code null}, or it opens with an early {@code return}. A ternary
 * is fine when it defaults an absent value, which is to say when its condition mentions
 * {@code null}.
 *
 * <p><b>Escape hatch</b>: {@code // ROOT-LOGIC-OK: <reason>} on the offending line or the line
 * above, with a NON-EMPTY reason. A bare marker fails just like the unmarked line would. Reach for
 * it only when the root genuinely cannot delegate; the first move is always to move the decision
 * into the module that owns it and leave the root filling a seam.
 */
class RootRegistrationOnlyTest {

    /** The root's whole source. Both files are registration surfaces and both are held to the rule. */
    private static final List<Path> ROOT_SOURCES = List.of(
            Path.of("src", "main", "java", "com", "ziggfreed", "common", "ZiggfreedCommonPlugin.java"),
            Path.of("src", "main", "java", "com", "ziggfreed", "common", "asset", "FrameworkAssetRegistrar.java"));

    /** Control flow that is a decision however it is written. */
    private static final Pattern DECISION = Pattern.compile("\\b(for|while|do|switch|else)\\b");

    /** A conditional, guard or not; whether it passes is decided by {@link #isGuard}. */
    private static final Pattern CONDITIONAL = Pattern.compile("\\bif\\b");

    /** A ternary. Allowed only as a null default - see the class javadoc. */
    private static final Pattern TERNARY = Pattern.compile("\\?[^?:]*:");

    /** The escape hatch. The reason after the colon must be non-empty. */
    private static final Pattern ALLOW_MARKER = Pattern.compile("//\\s*ROOT-LOGIC-OK:\\s*(\\S.*)?$");

    @Test
    void theWiringRootRegistersAndDecidesNothing() throws IOException {
        List<String> hits = new ArrayList<>();
        for (Path source : ROOT_SOURCES) {
            assertTrue(Files.isRegularFile(source), "missing root source: " + source.toAbsolutePath());
            scan(source, hits);
        }

        assertTrue(hits.isEmpty(), () -> "The wiring root grew logic (" + hits.size() + " hit(s)). Move the"
                + " decision into the module that owns it and leave the root filling a seam; if it truly"
                + " cannot move, mark the line // ROOT-LOGIC-OK: <reason>.\n" + String.join("\n", hits));
    }

    private static void scan(Path source, List<String> hits) throws IOException {
        List<String> raw = Files.readAllLines(source, StandardCharsets.UTF_8);
        List<String> code = strip(raw);
        for (int i = 0; i < code.size(); i++) {
            String line = code.get(i);
            if (line.isBlank() || isAllowed(raw, i)) {
                continue;
            }
            Matcher decision = DECISION.matcher(line);
            if (decision.find()) {
                hits.add(report(source, raw, i, decision.group()));
                continue;
            }
            if (CONDITIONAL.matcher(line).find() && !isGuard(line, code, i)) {
                hits.add(report(source, raw, i, "if that is not a guard"));
                continue;
            }
            if (TERNARY.matcher(line).find() && !line.contains("null")) {
                hits.add(report(source, raw, i, "ternary that is not a null default"));
            }
        }
    }

    /**
     * Blank out comments and string literals so prose and log text never trip the scan. Line-based
     * and deliberately simple: it tracks block comments across lines, which is all the root's own
     * formatting needs, and it does not try to be a Java lexer.
     */
    private static List<String> strip(List<String> raw) {
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
                if (inString) {
                    if (c == '\\') {
                        i++;
                    } else if (c == '"') {
                        inString = false;
                    }
                    continue;
                }
                if (c == '/' && next == '/') {
                    break;
                }
                if (c == '/' && next == '*') {
                    inBlock = true;
                    i++;
                    continue;
                }
                if (c == '"') {
                    inString = true;
                    continue;
                }
                sb.append(c);
            }
            out.add(sb.toString());
        }
        return out;
    }

    /** A conditional passes as a guard when it tests for absence, or opens with an early return. */
    private static boolean isGuard(String line, List<String> code, int index) {
        if (line.contains("null")) {
            return true;
        }
        for (int i = index; i < Math.min(code.size(), index + 3); i++) {
            String following = code.get(i).trim();
            if (i > index && following.startsWith("return")) {
                return true;
            }
            if (following.endsWith("return;") || following.contains("{ return")) {
                return true;
            }
        }
        return false;
    }

    /** The marker counts on the offending line or the one above it, and its reason must say something. */
    private static boolean isAllowed(List<String> raw, int index) {
        for (int i = Math.max(0, index - 1); i <= index; i++) {
            Matcher marker = ALLOW_MARKER.matcher(raw.get(i));
            if (marker.find() && marker.group(1) != null && !marker.group(1).isBlank()) {
                return true;
            }
        }
        return false;
    }

    private static String report(Path source, List<String> raw, int index, String what) {
        return source.getFileName() + ":" + (index + 1) + ": [" + what + "] " + raw.get(index).trim();
    }
}
