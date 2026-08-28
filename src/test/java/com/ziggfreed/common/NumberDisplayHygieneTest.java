package com.ziggfreed.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import javax.annotation.Nonnull;

import org.junit.jupiter.api.Test;

/**
 * No server-side-formatted NUMBER wrapped into a raw display {@code Message} - build-enforced, the
 * way the MMO's {@code RepoHygieneTest} enforces its own repo-wide bans (same line-based scan, same
 * marker-with-reason escape hatch).
 *
 * <p>The defect class: {@code Msg.raw(NumberFormatter.grouped(amount))} (or a
 * {@code String.format} feeding a raw {@code Message}) ships a number as literal text, so the
 * SERVER's digit grouping lands on every player's screen and the client's own locale never gets to
 * decide. A display number binds as a TYPED numeric param on a {@code {0, number}} lang key
 * ({@code Msg.num}, or a composition key like the commerce amount-and-name pair), so each player's
 * client formats it. This shipped once as pre-grouped currency amounts on reward rows, which is why
 * it is a test and not a review note.
 *
 * <p>A line containing {@code Msg.raw(} / {@code Message.raw(} together with a number-formatting
 * call ({@code NumberFormatter.*} or {@code String.format(}) fails, UNLESS the line, or the line
 * directly above it, carries a {@code // NUMBER-OK: <reason>} marker with a non-empty reason - the
 * escape hatch for a number that is genuinely not localized display text (a compact "446k" style
 * with no client-side format, an all-English operator console line). A bare marker with no reason
 * still fails. Detection is line-based: keep the formatting call on the {@code raw(} call's own
 * line, exactly as the sibling rules require of {@code Locale} arguments.
 */
class NumberDisplayHygieneTest {

    /** The wiring root's own sources plus every module's; a missing root is simply skipped. */
    private static final List<Path> SOURCE_ROOTS = sourceRoots();

    private static final Pattern RAW_MESSAGE_RE = Pattern.compile("\\b(?:Msg|Message)\\.raw\\(");

    private static final Pattern NUMBER_FORMATTING_RE =
            Pattern.compile("\\bNumberFormatter\\.|\\bString\\.format\\(");

    private static final Pattern NUMBER_OK_MARKER = Pattern.compile("//\\s*NUMBER-OK:\\s*(\\S.*)$");

    private record Violation(int lineNo, String line) {
    }

    @Test
    void noServerFormattedNumberInsideARawDisplayMessage() throws IOException {
        assertTrue(!SOURCE_ROOTS.isEmpty(), "no main-source roots found to scan");
        List<String> problems = new ArrayList<>();
        for (Path root : SOURCE_ROOTS) {
            try (Stream<Path> entries = Files.walk(root)) {
                for (Path p : entries.filter(f -> f.getFileName().toString().endsWith(".java"))
                        .sorted().toList()) {
                    List<String> lines;
                    try {
                        lines = Files.readAllLines(p, StandardCharsets.UTF_8);
                    } catch (IOException e) {
                        problems.add(p + ": unreadable - " + e.getMessage());
                        continue;
                    }
                    for (Violation v : scanLines(lines)) {
                        problems.add(p + ":" + v.lineNo() + ": " + v.line().trim());
                    }
                }
            }
        }
        assertTrue(problems.isEmpty(), () -> problems.size() + " server-formatted-number-in-raw-"
                + "Message violation(s) - a display number binds as a TYPED numeric param on a "
                + "{0, number} key (Msg.num, or a composition key) so each player's own client "
                + "formats it; never raw(NumberFormatter/String.format...). A number that is "
                + "genuinely not localized display text takes \"// NUMBER-OK: <reason>\" on its "
                + "own line or the line above (reason mandatory):\n" + String.join("\n", problems));
    }

    @Nonnull
    private static List<Path> sourceRoots() {
        List<Path> roots = new ArrayList<>();
        Path own = Path.of("src", "main", "java");
        if (Files.isDirectory(own)) {
            roots.add(own);
        }
        try (Stream<Path> modules = Files.list(Path.of("."))) {
            for (Path module : modules
                    .filter(m -> m.getFileName().toString().startsWith("zc-"))
                    .filter(Files::isDirectory).sorted().toList()) {
                Path nested = module.resolve("src").resolve("main").resolve("java");
                if (Files.isDirectory(nested)) {
                    roots.add(nested);
                }
            }
        } catch (IOException ignored) {
            // No module roots listable: the assertion above reports the empty scan.
        }
        return roots;
    }

    /**
     * The detection over an in-memory line list (file-I/O-free so the fixture tests below exercise
     * it directly). Same comment/string masking discipline as the MMO's {@code RepoHygieneTest}:
     * a mention inside a comment, a javadoc block, or a string literal is never a violation.
     */
    @Nonnull
    static List<Violation> scanLines(@Nonnull List<String> lines) {
        List<Violation> out = new ArrayList<>();
        boolean inBlockComment = false;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            boolean startedInBlockComment = inBlockComment;
            inBlockComment = updateBlockCommentState(line, inBlockComment);

            if (startedInBlockComment || isImportPackageOrLineComment(line)) {
                continue;
            }
            Matcher raw = RAW_MESSAGE_RE.matcher(line);
            if (!findUnmasked(raw, line)) {
                continue;
            }
            Matcher formatting = NUMBER_FORMATTING_RE.matcher(line);
            if (!findUnmasked(formatting, line)) {
                continue;
            }
            if (!hasNumberOkMarker(lines, i)) {
                out.add(new Violation(i + 1, line));
            }
        }
        return out;
    }

    /** Advance {@code m} to its first match outside a string literal / line comment, if any. */
    private static boolean findUnmasked(@Nonnull Matcher m, @Nonnull String line) {
        while (m.find()) {
            if (!isMasked(line, m.start())) {
                return true;
            }
        }
        return false;
    }

    /** The marker counts on the call's own line or the line directly above it. */
    private static boolean hasNumberOkMarker(@Nonnull List<String> lines, int index) {
        if (NUMBER_OK_MARKER.matcher(lines.get(index)).find()) {
            return true;
        }
        return index > 0 && NUMBER_OK_MARKER.matcher(lines.get(index - 1)).find();
    }

    private static boolean isImportPackageOrLineComment(@Nonnull String line) {
        String t = line.trim();
        return t.startsWith("import ") || t.startsWith("package ") || t.startsWith("//")
                || t.startsWith("*") || t.startsWith("/*");
    }

    /** True if position {@code idx} in {@code line} is inside a {@code "..."} literal or after {@code //}. */
    private static boolean isMasked(@Nonnull String line, int idx) {
        boolean inString = false;
        for (int i = 0; i < idx; i++) {
            char c = line.charAt(i);
            if (c == '"' && (i == 0 || line.charAt(i - 1) != '\\')) {
                inString = !inString;
            } else if (!inString && c == '/' && i + 1 < line.length() && line.charAt(i + 1) == '/') {
                return true;
            }
        }
        return inString;
    }

    /** Advances block-comment state across one line, string- and line-comment-aware. */
    private static boolean updateBlockCommentState(@Nonnull String line, boolean startingInBlockComment) {
        boolean blk = startingInBlockComment;
        boolean inStr = false;
        int j = 0;
        while (j < line.length()) {
            char c = line.charAt(j);
            if (!blk && !inStr && c == '"' && (j == 0 || line.charAt(j - 1) != '\\')) {
                inStr = true;
                j++;
                continue;
            }
            if (!blk && inStr && c == '"' && line.charAt(j - 1) != '\\') {
                inStr = false;
                j++;
                continue;
            }
            if (inStr) {
                j++;
                continue;
            }
            if (!blk && c == '/' && j + 1 < line.length() && line.charAt(j + 1) == '/') {
                break;
            }
            if (!blk && c == '/' && j + 1 < line.length() && line.charAt(j + 1) == '*') {
                blk = true;
                j += 2;
                continue;
            }
            if (blk && c == '*' && j + 1 < line.length() && line.charAt(j + 1) == '/') {
                blk = false;
                j += 2;
                continue;
            }
            j++;
        }
        return blk;
    }

    // ==================== fixtures: prove the rule works ====================

    @Test
    void flagsARawMessageWrappingAGroupedNumber() {
        List<Violation> v = scanLines(List.of(
                "public class Foo {",
                "    Message m = Msg.raw(NumberFormatter.grouped(amount));",
                "}"
        ));
        assertEquals(1, v.size());
        assertEquals(2, v.get(0).lineNo());
    }

    @Test
    void flagsAConcatenatedFormattingResultAndAStringFormat() {
        List<Violation> v = scanLines(List.of(
                "public class Foo {",
                "    Message m = Msg.cat(Msg.raw(NumberFormatter.grouped(amount) + \" \"), name);",
                "    Message n = Message.raw(String.format(Locale.ROOT, \"%,d\", n));",
                "}"
        ));
        assertEquals(2, v.size());
    }

    @Test
    void skipsARawMessageWithNoFormattingCallAndAFormattingCallWithNoRawMessage() {
        List<Violation> v = scanLines(List.of(
                "public class Foo {",
                "    Message m = Msg.raw(itemId);",
                "    String s = NumberFormatter.grouped(amount);",
                "}"
        ));
        assertTrue(v.isEmpty());
    }

    @Test
    void skipsALineMarkedNumberOkWithAReason() {
        List<Violation> v = scanLines(List.of(
                "public class Foo {",
                "    Message m = Msg.raw(NumberFormatter.compact(xp, 1_000)); // NUMBER-OK: compact k/M notation has no client-side number format",
                "    // NUMBER-OK: compact k/M notation has no client-side number format",
                "    Message n = Msg.raw(NumberFormatter.compact(xp, 1_000));",
                "}"
        ));
        assertTrue(v.isEmpty());
    }

    @Test
    void aBareNumberOkMarkerWithNoReasonStillFails() {
        List<Violation> v = scanLines(List.of(
                "public class Foo {",
                "    Message m = Msg.raw(NumberFormatter.grouped(amount)); // NUMBER-OK",
                "}"
        ));
        assertEquals(1, v.size());
    }

    @Test
    void skipsMentionsInCommentsJavadocAndStrings() {
        List<Violation> v = scanLines(List.of(
                "public class Foo {",
                "    // never Msg.raw(NumberFormatter.grouped(amount)) here",
                "    /**",
                "     * {@code Msg.raw(NumberFormatter.grouped(amount))} is banned.",
                "     */",
                "    String s = \"Msg.raw(NumberFormatter.grouped(amount))\";",
                "}"
        ));
        assertTrue(v.isEmpty());
    }
}
