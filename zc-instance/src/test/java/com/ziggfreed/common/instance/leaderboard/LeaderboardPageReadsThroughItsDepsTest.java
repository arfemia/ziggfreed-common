package com.ziggfreed.common.instance.leaderboard;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

/**
 * The page's two rules a unit JVM can hold without building a page (its static logger needs the
 * engine's log manager): a two-axis selection names its bucket through the deps, so a board that
 * composes its own keys (the encounter board's {@code <bucket>:<party>:<difficulty>}) reads them; and
 * the footer, the one parameterized line, is painted where its parameters are substituted.
 */
class LeaderboardPageReadsThroughItsDepsTest {

    private static final Path PAGE = Path.of("src", "main", "java", "com", "ziggfreed", "common", "instance",
            "leaderboard", "LeaderboardPage.java");

    @Test
    void theTwoAxesComposeThroughTheDepsNeverAHardWiredSeparator() throws IOException {
        String code = code();
        assertTrue(code.contains("deps.bucketKey("), "the cross product asks the deps for each bucket key");
        assertFalse(code.contains("+ \"_\" +"), "the page hard-wires no separator: the deps own the composition");
    }

    @Test
    void theFooterIsPaintedOnTextSpans() throws IOException {
        String code = code();
        assertTrue(code.contains("\"#YourRank.TextSpans\""),
                "the footer's rank and figure are parameters, which only TextSpans substitutes");
        assertFalse(code.contains("\"#YourRank.Text\""), "a parameterized footer on .Text prints its {0} at players");
    }

    /** The page's source with comment lines dropped, so prose never satisfies or trips a check. */
    private static String code() throws IOException {
        StringBuilder out = new StringBuilder();
        for (String line : Files.readAllLines(PAGE, StandardCharsets.UTF_8)) {
            String trimmed = line.trim();
            if (trimmed.startsWith("*") || trimmed.startsWith("/*") || trimmed.startsWith("//")) {
                continue;
            }
            out.append(line).append('\n');
        }
        return out.toString();
    }
}
