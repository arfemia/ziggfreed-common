package com.ziggfreed.common.track.stance;

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
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * The stance page's document and the Java that drives it, held together: a command against an id a document lacks
 * disconnects the player, so every id the page's Java spells is declared in the page's document or by the frame, and
 * the page builds on the kit's reading page, list, figures and empty state.
 */
class StancePageDocumentTest {

    private static final Path PAGES = Path.of("src", "main", "resources", "Common", "UI", "Custom", "Pages");

    private static final Path SOURCES = Path.of("src", "main", "java", "com", "ziggfreed", "common", "track",
            "stance");

    private static final Path FRAMES = Path.of("..", "zc-presentation", "src", "main", "resources", "Common", "UI",
            "Custom", "Common", "ZigFrames.ui");

    private static final Pattern STRING_LITERAL = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"");
    private static final Pattern ID = Pattern.compile("#([A-Za-z][A-Za-z0-9]*)");

    @Test
    void everyIdThePageAddressesIsDeclaredInItsDocument() throws IOException {
        String declared = code(PAGES.resolve("ZigTrackStancePage.ui")) + code(FRAMES);
        Set<String> spelled = new TreeSet<>();
        for (String driver : List.of("StancePage.java", "StancePagePlan.java")) {
            String java = Files.readString(SOURCES.resolve(driver), StandardCharsets.UTF_8)
                    .replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)^\\s*//.*$", "");
            Matcher literal = STRING_LITERAL.matcher(java);
            while (literal.find()) {
                Matcher id = ID.matcher(literal.group(1));
                while (id.find()) {
                    spelled.add(id.group());
                }
            }
        }
        assertFalse(spelled.isEmpty(), "the page addresses its document");
        List<String> missing = new ArrayList<>();
        for (String id : spelled) {
            if (!Pattern.compile("(?m)(^|\\s)(?:[A-Z][A-Za-z]*|\\$[A-Za-z]+\\.@[A-Za-z0-9]+)\\s+"
                    + Pattern.quote(id) + "\\s*\\{").matcher(declared).find()
                    && !Pattern.compile("(?m)^\\s*" + Pattern.quote(id) + "\\s*\\{").matcher(declared).find()) {
                missing.add(id);
            }
        }
        assertEquals(List.of(), missing, "ids the stance page's Java sets that its document does not declare");
    }

    @Test
    void theDocumentIsTheOnePlanNamesAndBuildsOnTheKit() throws IOException {
        assertTrue(Files.exists(PAGES.resolve(StancePagePlan.DOCUMENT.substring("Pages/".length()))),
                StancePagePlan.DOCUMENT + " ships");
        String page = code(PAGES.resolve("ZigTrackStancePage.ui"));
        assertTrue(page.contains("$ZW.@ZigDetailPage #Page"), "the reading page is the kit's");
        assertTrue(page.contains("$ZW.@ZigEmptyState #PageEmpty"), "the empty state is the kit's");
        assertTrue(page.contains("$ZW.@ZigStat #SlotsOpen") && page.contains("$ZW.@ZigStat #FacesHeld"),
                "the header's figures are the kit's");
        assertFalse(page.contains("TextButton"), "a button on the page is the kit's, never a TextButton");
        assertFalse(Pattern.compile("FontSize:\\s*\\d").matcher(page).find(), "every size is a type step");
    }

    /** A document with every {@code //} comment removed. */
    private static String code(Path file) throws IOException {
        StringBuilder out = new StringBuilder();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            int at = line.indexOf("//");
            out.append(at < 0 ? line : line.substring(0, at)).append('\n');
        }
        return out.toString();
    }
}
