package com.ziggfreed.common.objectives.title.page;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * The picker document against what the client's parser accepts (zc's own syntax test reads only
 * zc-presentation), every id the page addresses declared in it, and every picker line the page says
 * shipped in English. A document the client cannot parse fails at join, which no server check sees.
 */
class TitlePickerDocumentTest {

    private static final Path DOC = Path.of("src", "main", "resources", "Common", "UI", "Custom", "Pages",
            "ZigTitlePickerPage.ui");

    private static final Path PAGE_SOURCE = Path.of("src", "main", "java", "com", "ziggfreed", "common",
            "objectives", "title", "page", "TitlePickerPage.java");

    private static final Path ENGLISH = Path.of("src", "main", "resources", "Server", "Languages", "en-US",
            "ziggfreedcommon.title.lang");

    private static final Pattern ID = Pattern.compile("#([A-Za-z][A-Za-z0-9]*)");

    private static final Pattern ID_TOKEN = Pattern.compile("#([A-Za-z][A-Za-z0-9_.\\-]*)");

    private static final Pattern PICKER_KEY = Pattern.compile("TitleText\\.picker\\(\"([a-z_]+)\"");

    @Test
    void everyElementIdIsLettersAndDigitsOnly() throws IOException {
        List<String> bad = new ArrayList<>();
        for (String line : Files.readAllLines(DOC, StandardCharsets.UTF_8)) {
            Matcher m = ID_TOKEN.matcher(stripComment(line));
            while (m.find()) {
                String head = m.group(1).split("\\.")[0];
                if (!ID.matcher("#" + head).matches()) {
                    bad.add("#" + head);
                }
            }
        }
        assertEquals(List.of(), bad, "an id is a letter followed by letters and digits; the client stops at anything else");
    }

    @Test
    void theBracesBalance() throws IOException {
        int depth = 0;
        for (String line : Files.readAllLines(DOC, StandardCharsets.UTF_8)) {
            for (char c : stripComment(line).toCharArray()) {
                depth += c == '{' ? 1 : c == '}' ? -1 : 0;
                if (depth < 0) {
                    fail("the document closes a brace it never opened");
                }
            }
        }
        assertEquals(0, depth, "braces left open");
    }

    @Test
    void everyIdThePageAddressesIsDeclared() throws IOException {
        // Comments stripped, as the two checks above read the document: a comment that names an id
        // (the header names #Rows) is not a declaration of it.
        String doc = String.join("\n", Files.readAllLines(DOC, StandardCharsets.UTF_8).stream()
                .map(TitlePickerDocumentTest::stripComment).toList());
        // #CloseButton comes from the frame (@CloseButton = true); the rows' ids from the shared row templates.
        for (String id : List.of("#Title", "#Subtitle", "#Preview", "#Rows", "#BackButton")) {
            assertTrue(doc.contains(id + " "), "the page addresses " + id + ", which the document lacks;"
                    + " a command against a missing selector crashes the client");
        }
    }

    @Test
    void everyPickerLineThePageSaysShipsInEnglish() throws IOException {
        String page = Files.readString(PAGE_SOURCE, StandardCharsets.UTF_8);
        Set<String> spoken = new LinkedHashSet<>();
        Matcher m = PICKER_KEY.matcher(page);
        while (m.find()) {
            spoken.add("picker." + m.group(1));
        }
        spoken.add("picker." + TitlePickerRows.NONE_NOTE);
        assertTrue(spoken.size() >= 9, "the scan found almost nothing, so it is not scanning: " + spoken);

        Set<String> shipped = new TreeSet<>();
        for (String line : Files.readAllLines(ENGLISH, StandardCharsets.UTF_8)) {
            String trimmed = line.trim();
            int eq = trimmed.indexOf('=');
            if (!trimmed.isEmpty() && !trimmed.startsWith("#") && eq > 0) {
                shipped.add(trimmed.substring(0, eq).trim());
            }
        }
        List<String> missing = new ArrayList<>();
        for (String key : spoken) {
            if (!shipped.contains(key)) {
                missing.add(key);
            }
        }
        assertEquals(List.of(), missing, "picker lines with nothing to resolve them from");
    }

    private static String stripComment(String line) {
        int at = line.indexOf("//");
        return at < 0 ? line : line.substring(0, at);
    }
}
