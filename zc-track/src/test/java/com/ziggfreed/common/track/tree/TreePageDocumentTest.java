package com.ziggfreed.common.track.tree;

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

import javax.annotation.Nonnull;

import org.junit.jupiter.api.Test;

/**
 * The tree page's document and the Java that drives it, held together: a command against an id a document lacks
 * disconnects the player, so every id the page's Java spells is declared in the page's document or by the frame; a
 * kit template's child ids are the kit painters' to spell, never the page's.
 */
class TreePageDocumentTest {

    private static final Path PAGES = Path.of("src", "main", "resources", "Common", "UI", "Custom", "Pages");

    private static final Path SOURCES = Path.of("src", "main", "java", "com", "ziggfreed", "common", "track", "tree");

    /** The Java that addresses the document: the page and its plan. */
    private static final List<String> DRIVERS = List.of("TreePage.java", "TreePagePlan.java");

    private static final Path FRAMES = Path.of("..", "zc-presentation", "src", "main", "resources", "Common", "UI",
            "Custom", "Common", "ZigFrames.ui");

    private static final Pattern STRING_LITERAL = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"");
    private static final Pattern ID = Pattern.compile("#([A-Za-z][A-Za-z0-9]*)");
    private static final Pattern HEX = Pattern.compile("#[0-9a-fA-F]{6}(?:[0-9a-fA-F]{2})?(?![0-9A-Za-z])");

    @Test
    void everyIdThePageAddressesIsDeclaredInItsDocument() throws IOException {
        String declared = code(PAGES.resolve("ZigTrackTreePage.ui")) + code(FRAMES);
        Set<String> spelled = new TreeSet<>();
        for (String driver : DRIVERS) {
            Matcher literal = STRING_LITERAL.matcher(stripComments(
                    Files.readString(SOURCES.resolve(driver), StandardCharsets.UTF_8)));
            while (literal.find()) {
                Matcher id = ID.matcher(literal.group(1));
                while (id.find()) {
                    if (!HEX.matcher(id.group()).matches()) {
                        spelled.add(id.group());
                    }
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
        assertEquals(List.of(), missing, "ids the tree page's Java sets that its document does not declare");
    }

    @Test
    void theDocumentIsTheOnePlanNamesAndBuildsOnTheKit() throws IOException {
        assertTrue(Files.exists(PAGES.resolve(TreePagePlan.DOCUMENT.substring("Pages/".length()))),
                TreePagePlan.DOCUMENT + " ships");
        String page = code(PAGES.resolve("ZigTrackTreePage.ui"));
        assertTrue(page.contains("$ZW.@ZigDetailPage #Page"), "the reading page is the kit's");
        assertTrue(page.contains("$ZW.@ZigEmptyState #PageEmpty"), "the empty state is the kit's");
        assertTrue(page.contains("$ZW.@ZigStat #PointsSpent") && page.contains("$ZW.@ZigStat #PointsLeft"),
                "the header's figures are the kit's");
        assertFalse(page.contains("TextButton"), "a button on the page is the kit's or a rich button, never a TextButton");
        assertFalse(Pattern.compile("FontSize:\\s*\\d").matcher(page).find(), "every size is a type step");
    }

    /** Java with its comments removed, so an id named in prose is not taken for one the page sets. */
    @Nonnull
    private static String stripComments(@Nonnull String java) {
        String noBlocks = java.replaceAll("(?s)/\\*.*?\\*/", "");
        StringBuilder out = new StringBuilder();
        for (String line : noBlocks.split("\n", -1)) {
            int at = line.indexOf("//");
            out.append(at < 0 || line.substring(0, at).chars().filter(c -> c == '"').count() % 2 == 1 ? line
                    : line.substring(0, at)).append('\n');
        }
        return out.toString();
    }

    /** A document with every {@code //} comment removed. */
    @Nonnull
    private static String code(@Nonnull Path file) throws IOException {
        StringBuilder out = new StringBuilder();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            int at = line.indexOf("//");
            out.append(at < 0 ? line : line.substring(0, at)).append('\n');
        }
        return out.toString();
    }
}
