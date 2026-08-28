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
 * A UI element's {@code .Anchor} is an OBJECT slot, so it is written with {@code setObject} and a
 * real {@code Anchor}, never with {@code set} and the string an authored {@code .ui} file would
 * carry.
 *
 * <p>This is build-enforced because the failure is not a misdrawn row. The client refuses the value
 * and tears down the whole CustomUI update, which DISCONNECTS the player: a shop whose heading row
 * grew to fit a countdown dropped everyone who opened it, reading only as "CustomUI Set command
 * couldn't set value. Selector: #OfferList[0].Anchor" on the way out. Nothing in a server log said
 * why, and nothing in a build did either, which is what this test is for.
 *
 * <p>The scan and its comment/string masking are {@link NumberDisplayHygieneTest}'s, so the two
 * hygiene rules agree about what counts as code rather than each carrying their own reader.
 */
class ObjectSlotHygieneTest {

    private static final List<Path> SOURCE_ROOTS = NumberDisplayHygieneTest.sourceRoots();

    /**
     * A {@code cmd.set(...)} (never {@code setObject}) whose selector ends in an object-typed slot.
     * {@code .Anchor} is the one proven to disconnect; keep this list to slots actually shown to
     * behave that way rather than guessing at the whole property surface.
     */
    private static final Pattern OBJECT_SLOT_SET_RE =
            Pattern.compile("(?<!Object)\\bset\\(\\s*[^;]*\\.Anchor\"");

    private record Violation(int lineNo, String line) {
    }

    @Test
    void anObjectSlotIsNeverWrittenAsAString() throws IOException {
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
        assertTrue(problems.isEmpty(), () -> problems.size() + " object-slot-written-as-a-string "
                + "violation(s) - an .Anchor is built as an Anchor and pushed with "
                + "cmd.setObject(selector + \".Anchor\", anchor); a String there fails the whole "
                + "CustomUI update and disconnects the player:\n" + String.join("\n", problems));
    }

    /** The detection over an in-memory line list, so the fixtures below exercise it directly. */
    @Nonnull
    static List<Violation> scanLines(@Nonnull List<String> lines) {
        List<Violation> out = new ArrayList<>();
        boolean inBlockComment = false;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            boolean startedInBlockComment = inBlockComment;
            inBlockComment = NumberDisplayHygieneTest.updateBlockCommentState(line, inBlockComment);
            if (startedInBlockComment
                    || NumberDisplayHygieneTest.isImportPackageOrLineComment(line)) {
                continue;
            }
            Matcher m = OBJECT_SLOT_SET_RE.matcher(line);
            if (NumberDisplayHygieneTest.findUnmasked(m, line)) {
                out.add(new Violation(i + 1, line));
            }
        }
        return out;
    }

    // ==================== fixtures: prove the rule works ====================

    @Test
    void flagsAStringWrittenToAnAnchorSlot() {
        List<Violation> v = scanLines(List.of(
                "public class Foo {",
                "    void paint() { cmd.set(sel + \".Anchor\", \"(Height: 54, Bottom: 2)\"); }",
                "}"
        ));
        assertEquals(1, v.size());
        assertEquals(2, v.get(0).lineNo());
    }

    @Test
    void acceptsTheSetObjectForm() {
        List<Violation> v = scanLines(List.of(
                "public class Foo {",
                "    void paint() { cmd.setObject(sel + \".Anchor\", anchorWh(24, 24)); }",
                "}"
        ));
        assertTrue(v.isEmpty(), () -> "setObject is the correct form, got " + v);
    }

    @Test
    void ignoresAMentionInACommentOrAJavadocBlock() {
        List<Violation> v = scanLines(List.of(
                "/**",
                " * Never cmd.set(sel + \".Anchor\", \"...\") - it disconnects the player.",
                " */",
                "public class Foo {",
                "    // cmd.set(sel + \".Anchor\", tint);",
                "}"
        ));
        assertTrue(v.isEmpty(), () -> "a mention in prose is not a violation, got " + v);
    }
}
