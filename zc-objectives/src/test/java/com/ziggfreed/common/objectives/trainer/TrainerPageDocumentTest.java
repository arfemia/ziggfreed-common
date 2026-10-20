package com.ziggfreed.common.objectives.trainer;

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
 * The trainer page's documents and the Java that drives them, held together: a command against an id a document
 * lacks disconnects the player, so every id the page's Java spells is declared in the page's document, in the part
 * template, or by the frame; a kit template's child ids are the kit painters' to spell, never the page's.
 */
class TrainerPageDocumentTest {

    private static final Path PAGES = Path.of("src", "main", "resources", "Common", "UI", "Custom", "Pages");

    private static final Path SOURCES = Path.of("src", "main", "java", "com", "ziggfreed", "common", "objectives",
            "trainer");

    /** The Java that addresses the documents: the page, its plan, and the parts' painter. */
    private static final List<String> DRIVERS = List.of("TrainerPage.java", "TrainerPagePlan.java",
            "TrainerParts.java");

    private static final Path FRAMES = Path.of("..", "zc-presentation", "src", "main", "resources", "Common", "UI",
            "Custom", "Common", "ZigFrames.ui");

    private static final Pattern STRING_LITERAL = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"");
    private static final Pattern ID = Pattern.compile("#([A-Za-z][A-Za-z0-9]*)");
    private static final Pattern HEX = Pattern.compile("#[0-9a-fA-F]{6}(?:[0-9a-fA-F]{2})?(?![0-9A-Za-z])");

    @Test
    void everyIdThePageAddressesIsDeclaredInItsDocuments() throws IOException {
        String declared = code(PAGES.resolve("ZigTrainerPage.ui")) + code(PAGES.resolve("ZigTrainerSection.ui"))
                + code(FRAMES);
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
        assertFalse(spelled.isEmpty(), "the page addresses its documents");
        List<String> missing = new ArrayList<>();
        for (String id : spelled) {
            if (!Pattern.compile("(?m)(^|\\s)(?:[A-Z][A-Za-z]*|\\$[A-Za-z]+\\.@[A-Za-z0-9]+)\\s+"
                    + Pattern.quote(id) + "\\s*\\{").matcher(declared).find()
                    && !Pattern.compile("(?m)^\\s*" + Pattern.quote(id) + "\\s*\\{").matcher(declared).find()) {
                missing.add(id);
            }
        }
        assertEquals(List.of(), missing, "ids the trainer page's Java sets that its documents do not declare");
    }

    @Test
    void thePartTemplateIsWhatThePartsAppend() throws IOException {
        assertTrue(Files.exists(PAGES.resolve(TrainerParts.TEMPLATE.substring("Pages/".length()))),
                TrainerParts.TEMPLATE + " ships");
        String page = code(PAGES.resolve("ZigTrainerPage.ui"));
        assertTrue(page.contains("$ZW.@ZigDetailPage #Page"), "the reading page is the kit's");
        assertTrue(page.contains("$ZW.@ZigEmptyState #PageEmpty"), "the empty state is the kit's");
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
