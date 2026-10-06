package com.ziggfreed.common.objectives.book;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.Nonnull;

import org.junit.jupiter.api.Test;

/**
 * The book's two glyph-only buttons say what they do on hover, in vanilla's attested shape: the document
 * gives the plain {@code Button} the shared {@code TextTooltipStyle}, and the code that paints the row or
 * the detail header sets its {@code .TooltipText} from a shipped key (the way the engine's own trigger
 * volume inspector does). A tooltip with no style draws nothing; a key nothing ships reads as the key.
 */
class BookTooltipsTest {

    private static final Path PAGES = Path.of("src", "main", "resources", "Common", "UI", "Custom", "Pages");
    private static final Path BOOK = Path.of("src", "main", "java", "com", "ziggfreed", "common", "objectives", "book");
    private static final Path ENGLISH = Path.of("src", "main", "resources", "Server", "Languages", "en-US",
            "ziggfreedcommon.progression.lang");

    private record Glyph(String document, String button, String painter) {
    }

    private static final List<Glyph> GLYPHS = List.of(
            new Glyph("ZigQuestLogRow.ui", "#TrackBtn", "BookQuestsTab.java"),
            new Glyph("ZigAchListRow.ui", "#PinBtn", "BookAchievementsTab.java"),
            new Glyph("ZigObjectiveBookPage.ui", "#DPinBtn", "BookAchievementsTab.java"));

    @Test
    void eachGlyphButtonCarriesTheSharedTooltipStyle() throws IOException {
        for (Glyph glyph : GLYPHS) {
            String ui = code(PAGES.resolve(glyph.document()));
            assertTrue(ui.contains("$C = \"../Common.ui\";"), glyph.document() + " imports Common.ui as $C");
            assertTrue(own(block(ui, "Button " + glyph.button())).contains("TextTooltipStyle: $C.@DefaultTextTooltipStyle;"),
                    glyph.document() + " gives " + glyph.button() + " the shared tooltip style");
        }
    }

    @Test
    void thePainterSetsEachTooltipFromAShippedKey() throws IOException {
        for (Glyph glyph : GLYPHS) {
            String painter = Files.readString(BOOK.resolve(glyph.painter()), StandardCharsets.UTF_8);
            assertTrue(painter.contains(glyph.button() + ".TooltipText\""),
                    glyph.painter() + " sets " + glyph.button() + ".TooltipText");
        }
        String english = Files.readString(ENGLISH, StandardCharsets.UTF_8);
        for (String key : List.of("book.tooltip.track", "book.tooltip.pin")) {
            assertTrue(english.lines().anyMatch(line -> line.startsWith(key + " =")),
                    "ziggfreedcommon.progression.lang must author '" + key + "'");
        }
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

    /** The block {@code declaration} opens. */
    @Nonnull
    private static String block(@Nonnull String ui, @Nonnull String declaration) {
        Matcher m = Pattern.compile(Pattern.quote(declaration) + "\\s*\\{").matcher(ui);
        assertTrue(m.find(), "declares " + declaration);
        int depth = 0;
        for (int i = m.end() - 1; i < ui.length(); i++) {
            char c = ui.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return ui.substring(m.end() - 1, i + 1);
            }
        }
        throw new AssertionError("unbalanced braces in " + declaration);
    }

    /** A block's own properties, without the blocks nested in it. */
    @Nonnull
    private static String own(@Nonnull String block) {
        StringBuilder own = new StringBuilder();
        int depth = 0;
        for (char c : block.toCharArray()) {
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
            } else if (depth == 1) {
                own.append(c);
            }
        }
        return own.toString();
    }
}
