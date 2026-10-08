package com.ziggfreed.common.objectives.book;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

import com.ziggfreed.common.ui.menu.MenuFrame;

/**
 * The book's geometry is one set of numbers derived from the shared frame's body, and the shell document
 * spells the same numbers: the header band, the tab body every tab appends into, the stat blocks. A tab's
 * own document is held to the same constants by its own test.
 */
class LedgerLayoutTest {

    private static final Path SHELL = Path.of("src", "main", "resources", "Common", "UI", "Custom", "Pages",
            "ZigObjectiveBookPage.ui");

    @Test
    void theBookFillsTheMenuBodyLessItsOwnPadding() {
        assertEquals(MenuFrame.BODY_WIDTH - 2 * LedgerLayout.PANEL_PADDING, LedgerLayout.INNER_WIDTH);
        assertEquals(MenuFrame.FRAME_HEIGHT - 2 * MenuFrame.FRAME_PADDING - 2 * LedgerLayout.PANEL_PADDING,
                LedgerLayout.INNER_HEIGHT);
        assertEquals(1266, LedgerLayout.INNER_WIDTH, "the plan's inner width, from the frame's numbers");
        assertEquals(968, LedgerLayout.INNER_HEIGHT);
    }

    @Test
    void theHeaderToolbarAndSplitStackToTheInnerHeight() {
        assertEquals(LedgerLayout.INNER_HEIGHT,
                LedgerLayout.HEADER_HEIGHT + LedgerLayout.HEADER_GAP + LedgerLayout.BODY_HEIGHT);
        assertEquals(LedgerLayout.BODY_HEIGHT,
                LedgerLayout.TOOLBAR_HEIGHT + LedgerLayout.TOOLBAR_GAP + LedgerLayout.SPLIT_HEIGHT);
        assertEquals(852, LedgerLayout.SPLIT_HEIGHT);
    }

    @Test
    void theListGutterAndPageFillTheInnerWidth() {
        assertEquals(LedgerLayout.INNER_WIDTH, LedgerLayout.LIST_WIDTH + LedgerLayout.GUTTER + LedgerLayout.PAGE_WIDTH);
        assertEquals(LedgerLayout.LIST_WIDTH - LedgerLayout.SCROLL_GUTTER, LedgerLayout.ROW_WIDTH);
        assertEquals(750, LedgerLayout.PAGE_WIDTH);
        assertEquals(492, LedgerLayout.ROW_WIDTH);
        assertTrue(LedgerLayout.STATS * LedgerLayout.STAT_WIDTH + (LedgerLayout.STATS - 1) * LedgerLayout.STAT_GAP
                < LedgerLayout.INNER_WIDTH / 2, "the stats leave the title most of the band");
    }

    @Test
    void theShellDocumentSpellsTheSameNumbers() throws IOException {
        String ui = code(SHELL);
        assertTrue(own(block(ui, "Group #RightPanel")).contains("Padding: (Full: " + LedgerLayout.PANEL_PADDING + ")"),
                "#RightPanel pads by PANEL_PADDING");
        assertTrue(own(block(ui, "Group #Header")).contains(
                "Anchor: (Height: " + LedgerLayout.HEADER_HEIGHT + ", Bottom: " + LedgerLayout.HEADER_GAP + ")"),
                "the header band and its gap");
        assertTrue(own(block(ui, "Group #TabBody")).contains("Anchor: (Height: " + LedgerLayout.BODY_HEIGHT + ")"),
                "the tab body every tab appends into");
        for (int i = 0; i < LedgerLayout.STATS; i++) {
            assertTrue(ui.contains("$ZW.@ZigStat #Stat" + i + " "), "the header declares stat " + i);
        }
        assertTrue(own(block(ui, "Group #StatGap0")).contains("Anchor: (Width: " + LedgerLayout.STAT_GAP + ")"));
    }

    /**
     * The header band leads with the owner's logo as ONE scaled picture, never a branding block (M362, M370): an
     * {@code AssetImage #BrandingLogo}, the band's first child, before {@code #TitleBlock}; hidden, with a blank
     * fallback, since a consumer's right-mode painter sets its {@code .AssetPath} and shows it; the logo's 260:97
     * shape at the band's height, with a gap before the title. The title block keeps its three ids.
     */
    @Test
    void theHeaderLeadsWithTheOwnersLogo() throws IOException {
        String header = block(code(SHELL), "Group #Header");
        int logo = header.indexOf("AssetImage #BrandingLogo {");
        int titleBlock = header.indexOf("Group #TitleBlock {");
        assertTrue(logo > 0 && logo < titleBlock, "the logo comes before the title block");
        assertFalse(header.substring(1, logo).contains("{"), "the logo is the band's first child");

        String image = own(block(header, "AssetImage #BrandingLogo"));
        assertTrue(image.contains("Visible: false"), "the logo ships hidden until a painter shows it");
        assertTrue(image.contains("FallbackTexturePath: \"UI/Custom/Common/Glyphs/Blank.png\""),
                "an owner with no logo draws nothing, not a red X");
        Matcher anchor = Pattern.compile("Anchor:\\s*\\(([^)]*)\\)").matcher(image);
        assertTrue(anchor.find(), "the logo has an Anchor");
        assertEquals(LedgerLayout.HEADER_HEIGHT, leaf(anchor.group(1), "Height"), "the logo fills the band's height");
        assertEquals(Math.round(LedgerLayout.HEADER_HEIGHT * 260 / 97.0), leaf(anchor.group(1), "Width"),
                "the logo keeps its 260:97 shape at that height");
        assertTrue(leaf(anchor.group(1), "Right") > 0, "a gap between the logo and the title");

        String title = block(header, "Group #TitleBlock");
        for (String id : List.of("Group #TitleContainer {", "Label #PanelTitle {", "Label #PageSubtitle {",
                "Label #BrandingDescriptionRight {")) {
            assertTrue(title.contains(id), "the title block keeps " + id);
        }
        assertFalse(title.contains("#BrandingLogo"), "one logo, in the band, not a second in the title row");
    }

    @Test
    void everyIdTheShellAndThePlaceholderTabsAddressIsDeclared() throws IOException {
        String shell = code(SHELL);
        for (String id : List.of("AssetImage #BrandingLogo", "Label #PanelTitle", "Group #TitleContainer",
                "Label #BrandingDescriptionRight", "Label " + BookHeader.SUBTITLE, "Group " + BookContext.TAB_BODY,
                "$ZW.@ZigEmptyState " + ObjectiveBookPage.NO_PROGRESS)) {
            assertTrue(shell.contains(id + " {"), "the shell declares " + id);
        }
        for (String host : List.of("#BrandingLogo", "#TitleContainer", "#PanelTitle", "#BrandingDescriptionRight")) {
            assertEquals(1, Pattern.compile("\\S+\\s+" + Pattern.quote(host) + "\\s*\\{").matcher(shell).results().count(),
                    "the header declares the branding host " + host + " exactly once");
        }
        for (int i = 0; i < LedgerLayout.STATS; i++) {
            assertTrue(shell.contains("$ZW.@ZigStat " + BookHeader.statSelector(i) + " {"));
        }
        assertTrue(shell.contains("$ZW = \"../Common/ZigKit.ui\";"), "the kit is imported as $ZW");

        Path pages = SHELL.getParent();
        assertTrue(code(pages.resolve("ZigBookJournal.ui")).contains("$ZW.@ZigEmptyState #JournalEmpty {"));
        assertTrue(code(pages.resolve("ZigBookAchievements.ui")).contains("$ZW.@ZigEmptyState #AchievementsEmpty {"));
        for (String tab : List.of("ZigBookJournal.ui", "ZigBookAchievements.ui")) {
            String ui = code(pages.resolve(tab));
            assertTrue(ui.contains("Anchor: (Height: " + LedgerLayout.BODY_HEIGHT + ");"),
                    tab + " fills the tab body");
        }
    }

    /** An integer leaf of an object value's contents ({@code Width: 4, Right: 8}); fails when it is not there. */
    private static long leaf(@Nonnull String object, @Nonnull String name) {
        Matcher m = Pattern.compile("(?<![\\w@])" + Pattern.quote(name) + "\\s*:\\s*(-?\\d+)").matcher(object);
        assertTrue(m.find(), "(" + object + ") says " + name);
        return Long.parseLong(m.group(1));
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
