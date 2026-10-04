package com.ziggfreed.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import javax.annotation.Nonnull;

import org.junit.jupiter.api.Test;

/**
 * Every library main source keeps the engine seams: the engine's event-title util is called only from
 * zc-presentation's {@code feedback/EventTitles}, the engine's in-place page update only from
 * {@code ui/toast/ToastablePage} (its {@code writeInPlace}), and the two names Update 7 renamed
 * ({@code PageManager.updateCustomPage}, {@code protocol.DurabilityOperator}) appear nowhere.
 *
 * <p><b>Why it is worth a build failure.</b> Each seam is where the next engine rename lands: the
 * banner's boolean overloads and the page update moved under Update 7, and a second caller is a second
 * place that move breaks, silently, since every caller sits in a catch. The renamed names fail the
 * compile on Update 7 anyway; the scan says what to use instead.
 *
 * <p><b>What is scanned</b>: every {@code .java} file under the wiring root's and every module's
 * {@code src/main/java}, found by walking, so a new module is covered the day it appears. Comments,
 * string and character literals and text blocks are blanked first, so prose naming a rule never trips
 * it. There is no escape marker: a call that needs the engine directly belongs inside its seam.
 */
class EngineSeamHygieneTest {

    /** One rule: a code pattern, the files allowed to hold it (library-root-relative, forward slashes), the fix. */
    private record Rule(@Nonnull String name, @Nonnull Pattern pattern, @Nonnull Set<String> allowedFiles,
                        @Nonnull String fix) {
    }

    static final String EVENT_TITLES =
            "zc-presentation/src/main/java/com/ziggfreed/common/feedback/EventTitles.java";
    static final String TOASTABLE_PAGE =
            "zc-presentation/src/main/java/com/ziggfreed/common/ui/toast/ToastablePage.java";

    private static final List<Rule> RULES = List.of(
            new Rule("the engine's EventTitleUtil outside EventTitles", Pattern.compile("\\bEventTitleUtil\\b"),
                    Set.of(EVENT_TITLES),
                    "show a banner through zc-presentation's feedback/EventTitles, the library's one caller of it"),
            new Rule("the engine's in-place page update outside ToastablePage",
                    Pattern.compile("\\bupdateLegacyCustomPage\\s*\\("), Set.of(TOASTABLE_PAGE),
                    "write an in-place page update through ToastablePage.writeInPlace"),
            new Rule("PageManager.updateCustomPage, which Update 7 renamed",
                    Pattern.compile("\\bupdateCustomPage\\s*\\("), Set.of(),
                    "write an in-place page update through ToastablePage.writeInPlace"),
            new Rule("protocol.DurabilityOperator, which Update 7 renamed",
                    Pattern.compile("\\bDurabilityOperator\\b"), Set.of(),
                    "name ComparisonOperator, the type of DurabilityConditionInteraction.operator"));

    private static final String ELSEWHERE = "zc-instance/src/main/java/com/ziggfreed/common/lobby/Elsewhere.java";

    @Test
    void everyLibraryMainSourceKeepsTheEngineSeams() throws IOException {
        Path root = Path.of(".");
        assertTrue(Files.isRegularFile(root.resolve(EVENT_TITLES)) && Files.isRegularFile(root.resolve(TOASTABLE_PAGE)),
                "the two seams must exist where the rules allow them, under " + root.toAbsolutePath());
        List<Path> sources = mainSources(root);
        assertFalse(sources.isEmpty(), "no main source found under " + root.toAbsolutePath());
        List<String> found = new ArrayList<>();
        for (Path source : sources) {
            found.addAll(violations(relative(root, source), Files.readAllLines(source, StandardCharsets.UTF_8)));
        }
        assertTrue(found.isEmpty(), () -> found.size() + " engine-seam violation(s):\n" + String.join("\n", found));
    }

    // ==================== fixtures: prove each rule ====================

    @Test
    void theTitleUtilIsFlaggedOutsideEventTitlesAndPassesInsideIt() {
        List<String> call = List.of("        EventTitleUtil.showEventTitleToPlayer(p, a, b, EventTitleStyle.Major);");
        assertEquals(1, violations(ELSEWHERE, call).size());
        assertEquals(0, violations(EVENT_TITLES, call).size());
        assertEquals(1, violations(ELSEWHERE, List.of("import com.hypixel.hytale.server.core.util.EventTitleUtil;")).size(),
                "an import alone is a direct use");
    }

    @Test
    void thePageWriteIsFlaggedOutsideToastablePageAndTheRenamedOneEverywhere() {
        List<String> write = List.of("            p.getPageManager().updateLegacyCustomPage(page);");
        assertEquals(1, violations(ELSEWHERE, write).size());
        assertEquals(0, violations(TOASTABLE_PAGE, write).size());
        List<String> renamed = List.of("            p.getPageManager().updateCustomPage(page);");
        assertEquals(1, violations(TOASTABLE_PAGE, renamed).size(), "no file keeps the 0.6.8 name, the seam included");
    }

    @Test
    void theRenamedOperatorTypeIsFlaggedAndItsSuccessorPasses() {
        assertEquals(1, violations(ELSEWHERE, List.of("import com.hypixel.hytale.protocol.DurabilityOperator;")).size());
        assertEquals(1, violations(ELSEWHERE, List.of("        DurabilityOperator operator = wear.operator;")).size());
        assertEquals(0, violations(ELSEWHERE, List.of("        ComparisonOperator operator = wear.operator;")).size());
    }

    @Test
    void proseInCommentsJavadocAndLiteralsNeverTripsARule() {
        assertEquals(List.of(), violations(ELSEWHERE, List.of(
                "    // never EventTitleUtil here, nor updateCustomPage(page)",
                "    /**",
                "     * {@code DurabilityOperator} and updateLegacyCustomPage( stay in prose.",
                "     */",
                "    String s = \"EventTitleUtil.showEventTitleToPlayer(\";",
                "    char quote = '\"'; int n = 1; /* EventTitleUtil */ int m = 2;",
                "    String block = \"\"\"",
                "        p.getPageManager().updateCustomPage(page)",
                "        \"\"\";")));
    }

    @Test
    void codeAfterACharacterLiteralOrAClosedCommentIsStillRead() {
        assertEquals(1, violations(ELSEWHERE,
                List.of("    char quote = '\"'; EventTitleUtil.hideEventTitleFromPlayer(p, 1.5F);")).size());
        assertEquals(1, violations(ELSEWHERE, List.of("    /* a note */ DurabilityOperator op = null;")).size());
    }

    // ==================== the scan ====================

    /** The violations in one file's lines; {@code relativePath} is library-root-relative, forward slashes. */
    @Nonnull
    static List<String> violations(@Nonnull String relativePath, @Nonnull List<String> lines) {
        List<String> code = blankCommentsAndLiterals(lines);
        List<String> out = new ArrayList<>();
        for (int i = 0; i < code.size(); i++) {
            for (Rule rule : RULES) {
                if (!rule.allowedFiles().contains(relativePath) && rule.pattern().matcher(code.get(i)).find()) {
                    out.add(relativePath + ":" + (i + 1) + ": " + rule.name() + "; " + rule.fix() + ": "
                            + lines.get(i).trim());
                }
            }
        }
        return out;
    }

    /** The wiring root's and every module's main sources, outside any build output, in a stable order. */
    @Nonnull
    private static List<Path> mainSources(@Nonnull Path root) throws IOException {
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".java"))
                    .filter(p -> {
                        String path = "/" + relative(root, p);
                        return path.contains("/src/main/java/") && !path.contains("/build/");
                    })
                    .sorted()
                    .toList();
        }
    }

    @Nonnull
    private static String relative(@Nonnull Path root, @Nonnull Path file) {
        return root.toAbsolutePath().normalize().relativize(file.toAbsolutePath().normalize()).toString()
                .replace('\\', '/');
    }

    /** Blank comments, string and character literals and text blocks; comment and text-block state cross lines. */
    @Nonnull
    static List<String> blankCommentsAndLiterals(@Nonnull List<String> raw) {
        List<String> out = new ArrayList<>(raw.size());
        boolean inBlockComment = false;
        boolean inTextBlock = false;
        for (String line : raw) {
            StringBuilder code = new StringBuilder();
            int i = 0;
            while (i < line.length()) {
                char c = line.charAt(i);
                char next = i + 1 < line.length() ? line.charAt(i + 1) : '\0';
                if (inBlockComment) {
                    if (c == '*' && next == '/') {
                        inBlockComment = false;
                        i += 2;
                    } else {
                        i++;
                    }
                    continue;
                }
                if (inTextBlock) {
                    if (line.startsWith("\"\"\"", i)) {
                        inTextBlock = false;
                        i += 3;
                    } else {
                        i += c == '\\' ? 2 : 1;
                    }
                    continue;
                }
                if (c == '/' && next == '*') {
                    inBlockComment = true;
                    i += 2;
                    continue;
                }
                if (c == '/' && next == '/') {
                    break;
                }
                if (line.startsWith("\"\"\"", i)) {
                    inTextBlock = true;
                    i += 3;
                    continue;
                }
                if (c == '"' || c == '\'') {
                    i = endOfLiteral(line, i, c);
                    code.append(' ');
                    continue;
                }
                code.append(c);
                i++;
            }
            out.add(code.toString());
        }
        return out;
    }

    /** The index just past the literal {@code quote} opens at {@code start}, or the line's end if it never closes. */
    private static int endOfLiteral(@Nonnull String line, int start, char quote) {
        int i = start + 1;
        while (i < line.length()) {
            char c = line.charAt(i);
            if (c == '\\') {
                i += 2;
            } else if (c == quote) {
                return i + 1;
            } else {
                i++;
            }
        }
        return line.length();
    }
}
