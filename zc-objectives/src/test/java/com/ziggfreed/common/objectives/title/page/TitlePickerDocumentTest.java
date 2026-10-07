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
 * The picker's two documents against what the client's parser accepts, every id the page's Java addresses declared
 * in them (a command against a missing selector disconnects the player), the earned grid's layout written in one
 * place, and every picker line the page says shipped in English. A document the client cannot parse fails at join,
 * which no server check sees.
 */
class TitlePickerDocumentTest {

    private static final Path PAGES = Path.of("src", "main", "resources", "Common", "UI", "Custom", "Pages");

    private static final Path DOC = PAGES.resolve("ZigTitlePickerPage.ui");

    private static final Path TILE = PAGES.resolve("ZigTitleTile.ui");

    private static final Path SOURCES = Path.of("src", "main", "java", "com", "ziggfreed", "common",
            "objectives", "title", "page");

    private static final Path PAGE_SOURCE = SOURCES.resolve("TitlePickerPage.java");

    private static final Path ROWS_SOURCE = SOURCES.resolve("TitlePickerRows.java");

    private static final Path ENGLISH = Path.of("src", "main", "resources", "Server", "Languages", "en-US",
            "ziggfreedcommon.title.lang");

    private static final Pattern ID = Pattern.compile("#([A-Za-z][A-Za-z0-9]*)");

    private static final Pattern ID_TOKEN = Pattern.compile("#([A-Za-z][A-Za-z0-9_.\\-]*)");

    /** A string literal in Java source. */
    private static final Pattern LITERAL = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"");

    private static final Pattern PICKER_KEY = Pattern.compile("TitleText\\.picker\\(\"([a-z_]+)\"");

    /** Ids the page sets that its own documents do not declare: the frame's close button. */
    private static final Set<String> FROM_THE_FRAME = Set.of("CloseButton");

    @Test
    void everyElementIdIsLettersAndDigitsOnly() throws IOException {
        for (Path doc : List.of(DOC, TILE)) {
            List<String> bad = new ArrayList<>();
            for (String line : Files.readAllLines(doc, StandardCharsets.UTF_8)) {
                Matcher m = ID_TOKEN.matcher(stripComment(line));
                while (m.find()) {
                    String head = m.group(1).split("\\.")[0];
                    if (!ID.matcher("#" + head).matches()) {
                        bad.add("#" + head);
                    }
                }
            }
            assertEquals(List.of(), bad, doc.getFileName()
                    + ": an id is a letter followed by letters and digits; the client stops at anything else");
        }
    }

    @Test
    void theBracesBalance() throws IOException {
        for (Path doc : List.of(DOC, TILE)) {
            int depth = 0;
            for (String line : Files.readAllLines(doc, StandardCharsets.UTF_8)) {
                for (char c : stripComment(line).toCharArray()) {
                    depth += c == '{' ? 1 : c == '}' ? -1 : 0;
                    if (depth < 0) {
                        fail(doc.getFileName() + " closes a brace it never opened");
                    }
                }
            }
            assertEquals(0, depth, doc.getFileName() + ": braces left open");
        }
    }

    @Test
    void everyIdThePageAddressesIsDeclared() throws IOException {
        String declared = code(DOC) + "\n" + code(TILE);
        Set<String> addressed = new TreeSet<>();
        for (Path source : List.of(PAGE_SOURCE, ROWS_SOURCE)) {
            Matcher literal = LITERAL.matcher(Files.readString(source, StandardCharsets.UTF_8));
            while (literal.find()) {
                Matcher id = ID.matcher(literal.group(1));
                while (id.find()) {
                    addressed.add(id.group(1));
                }
            }
        }
        assertTrue(addressed.size() >= 15, "the scan found almost nothing, so it is not scanning: " + addressed);

        List<String> missing = new ArrayList<>();
        for (String id : addressed) {
            if (!FROM_THE_FRAME.contains(id) && !Pattern.compile("#" + id + "\\s*\\{").matcher(declared).find()) {
                missing.add("#" + id);
            }
        }
        assertEquals(List.of(), missing, "the page addresses these, which neither document declares;"
                + " a command against a missing selector disconnects the player");
    }

    @Test
    void everyLabelledButtonIsAButtonHoldingItsLabel() throws IOException {
        String page = code(DOC);
        for (String id : List.of("BackButton", "ShowNone")) {
            assertTrue(block(page, "Button #" + id).contains("Label #Label"), "#" + id + " holds Label #Label");
        }
        assertTrue(block(code(TILE), "Button #Show").contains("Label #Label"), "#Show holds Label #Label");
        assertTrue(!page.contains("TextButton") && !code(TILE).contains("TextButton"), "no TextButton");
    }

    @Test
    void theTileIsItsOwnRootAtTwoHundredEightySixByEightyEight() throws IOException {
        String root = block(code(TILE), "Group #ZigTitleTile");
        assertTrue(root.contains("Width: 286") && root.contains("Height: 88"), "the tile is 286 x 88: " + root);
    }

    @Test
    void theTileSitsOnVanillasMemoriesTileAndTheShownTitleOnItsCompleteFace() throws IOException {
        String tile = code(TILE);
        String rest = block(tile, "Group #FaceRest");
        String shown = block(tile, "Group #FaceShown");
        assertTrue(rest.matches(memoriesFace("TileDefault")),
                "a title on offer sits on the Memories default tile, nine-sliced at 8: " + rest);
        assertTrue(!rest.contains("Visible: false"), "the default face shows until Java says otherwise");
        assertTrue(shown.matches(memoriesFace("TileComplete")),
                "the title shown now sits on the Memories complete tile: " + shown);
        assertTrue(shown.contains("Visible: false"), "the complete face starts hidden");
    }

    @Test
    void theEarnedGridWritesItsWrappingLayoutOnce() throws IOException {
        String page = code(DOC);
        Matcher wrap = Pattern.compile("LayoutMode:\\s*(LeftWrap|LeftCenterWrap)\\s*;").matcher(page);
        assertTrue(wrap.find(), "the earned grid wraps");
        assertTrue(!wrap.find(), "the wrapping layout is written in one place, so a change to it is one line");
        assertTrue(block(page, "Group #Earned").matches("(?s).*LayoutMode:\\s*(LeftWrap|LeftCenterWrap)\\s*;.*"),
                "the one wrapping layout is the earned grid's");
    }

    @Test
    void everyTextSizeIsAStepOfTheTypeScale() throws IOException {
        for (Path doc : List.of(DOC, TILE)) {
            Matcher number = Pattern.compile("FontSize:\\s*\\d").matcher(code(doc));
            assertTrue(!number.find(), doc.getFileName() + " writes a size as a number; it takes a $ZT step");
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
        assertTrue(spoken.size() >= 12, "the scan found almost nothing, so it is not scanning: " + spoken);

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

    /** A block whose background is vanilla's Memories tile {@code texture}, nine-sliced at 8. */
    private static String memoriesFace(String texture) {
        return "(?s).*TexturePath:\\s*\"\\.\\./Pages/Memories/Tiles/" + texture + "\\.png\",\\s*Border:\\s*8\\b.*";
    }

    /** The document with its comments taken out: a comment that names an id does not declare it. */
    private static String code(Path doc) throws IOException {
        return String.join("\n", Files.readAllLines(doc, StandardCharsets.UTF_8).stream()
                .map(TitlePickerDocumentTest::stripComment).toList());
    }

    /** The braced block that follows {@code head}, braces included; fails when the document lacks it. */
    private static String block(String code, String head) {
        Matcher at = Pattern.compile(Pattern.quote(head) + "\\s*\\{").matcher(code);
        if (!at.find()) {
            fail("the document declares no " + head);
        }
        int depth = 0;
        for (int i = at.end() - 1; i < code.length(); i++) {
            char c = code.charAt(i);
            depth += c == '{' ? 1 : c == '}' ? -1 : 0;
            if (depth == 0) {
                return code.substring(at.start(), i + 1);
            }
        }
        fail(head + " is never closed");
        return "";
    }

    private static String stripComment(String line) {
        int at = line.indexOf("//");
        return at < 0 ? line : line.substring(0, at);
    }
}
