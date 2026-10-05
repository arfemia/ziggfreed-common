package com.ziggfreed.common.instance;

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

import org.junit.jupiter.api.Test;

/**
 * Every page in this module names a player through zc-presentation's display-name seam, on a Label's
 * {@code .TextSpans}: a page that reads {@code getUsername()} for a row shows no title, and a name on
 * {@code .Text} prints a decorated name's {@code {0}} at players. A bare username kept as data (the
 * invite search's haystack) carries {@code // NAME-DATA-OK: <reason>} on its line or the one above.
 */
class PlayerNamesGoThroughTheSeamTest {

    private static final Path SOURCES = Path.of("src", "main", "java");

    private static final Pattern RAW_USERNAME = Pattern.compile("\\.getUsername\\(\\)");

    private static final Pattern NAME_ON_TEXT = Pattern.compile("#(Player|RowName)\\.Text\"");

    private static final Pattern ALLOW_MARKER = Pattern.compile("//\\s*NAME-DATA-OK:\\s*(\\S.*)?$");

    @Test
    void everyPageNamesAPlayerThroughTheDisplaySeamOnTextSpans() throws IOException {
        List<Path> pages = pages();
        assertTrue(pages.size() >= 4, "the walk found almost no pages, so it is not walking: " + pages);

        List<String> hits = new ArrayList<>();
        for (Path page : pages) {
            List<String> lines = Files.readAllLines(page, StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size(); i++) {
                String code = codeOf(lines.get(i));
                if (!RAW_USERNAME.matcher(code).find() && !NAME_ON_TEXT.matcher(code).find()) {
                    continue;
                }
                boolean marked = allowed(lines.get(i))
                        || (i > 0 && lines.get(i - 1).trim().startsWith("//") && allowed(lines.get(i - 1)));
                if (!marked) {
                    hits.add(page.getFileName() + ":" + (i + 1) + "  " + lines.get(i).trim());
                }
            }
        }
        assertEquals(List.of(), hits, "Name a player with PlayerDisplayNames.displayName on a Label's"
                + " .TextSpans, or mark a bare username kept as data // NAME-DATA-OK: <reason>");
    }

    private static boolean allowed(String line) {
        Matcher m = ALLOW_MARKER.matcher(line);
        return m.find() && m.group(1) != null && !m.group(1).isBlank();
    }

    /** A line's code: javadoc and block-comment lines read as nothing, a trailing // comment is cut. */
    private static String codeOf(String line) {
        String trimmed = line.trim();
        if (trimmed.startsWith("*") || trimmed.startsWith("/*") || trimmed.startsWith("//")) {
            return "";
        }
        int at = line.indexOf("//");
        return at < 0 ? line : line.substring(0, at);
    }

    private static List<Path> pages() throws IOException {
        assertTrue(Files.isDirectory(SOURCES), "missing " + SOURCES.toAbsolutePath());
        try (Stream<Path> walk = Files.walk(SOURCES)) {
            return walk.filter(p -> p.getFileName().toString().endsWith("Page.java")).sorted().toList();
        }
    }
}
