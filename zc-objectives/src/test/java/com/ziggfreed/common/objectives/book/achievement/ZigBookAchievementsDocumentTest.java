package com.ziggfreed.common.objectives.book.achievement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.Nonnull;

import org.junit.jupiter.api.Test;

import com.ziggfreed.common.objectives.book.LedgerLayout;
import com.ziggfreed.common.ui.kit.ViewTabPainter;

/**
 * The Achievements tab's document ({@code Pages/ZigBookAchievements.ui}) holds every id the tab addresses, each once
 * (a command against an id the document lacks disconnects the player), spells the tab's numbers ({@link LedgerLayout}
 * for the toolbar, the list and the page; the tab's own for the overview's cards, strips and tiles, which together
 * fill the scrolling overview's width), and keeps to the client's grammar: ids of letters and digits, vanilla's
 * layout modes and alignments, sizes only from named text styles, every kit name resolving, no item icon widget, and
 * every labelled button a {@code Button} holding a {@code Label #Label}.
 */
class ZigBookAchievementsDocumentTest {

    private static final Path PAGES = Path.of("src", "main", "resources", "Common", "UI", "Custom", "Pages");
    private static final Path DOCUMENT = PAGES.resolve("ZigBookAchievements.ui");
    private static final Path KIT_COMMON = Path.of("..", "zc-presentation", "src", "main", "resources", "Common", "UI",
            "Custom", "Common");
    private static final Path ENGLISH = Path.of("src", "main", "resources", "Server", "Languages", "en-US",
            "ziggfreedcommon.progression.lang");
    private static final String PROGRESSION = "ziggfreedcommon.progression.";

    private static final Set<String> LAYOUT_MODES = Set.of("Top", "Bottom", "Left", "Right", "Center", "Middle",
            "CenterMiddle", "MiddleCenter", "Full", "TopScrolling", "BottomScrolling", "LeftScrolling",
            "RightScrolling", "LeftCenterWrap", "LeftWrap");

    @Test
    void everyIdTheTabAddressesIsDeclaredOnce() throws IOException {
        String ui = code(DOCUMENT);
        for (String id : AchievementsTab.ADDRESSED) {
            Matcher m = Pattern.compile("(?m)^\\s*[$@\\w.]+ " + Pattern.quote(id) + " \\{").matcher(ui);
            int count = 0;
            while (m.find()) {
                count++;
            }
            assertEquals(1, count, "the document declares " + id + " exactly once");
        }
    }

    @Test
    void theKitPiecesAreTheKitsOwn() throws IOException {
        String ui = code(DOCUMENT);
        Map<String, String> instances = new LinkedHashMap<>();
        instances.put(AchievementsTab.PAGE, "$ZW.@ZigDetailPage");
        instances.put(AchievementsTab.PAGE_EMPTY, "$ZW.@ZigEmptyState");
        instances.put(AchievementsTab.LIST_EMPTY, "$ZW.@ZigEmptyState");
        instances.put(AchievementsTab.EMPTY, "$ZW.@ZigEmptyState");
        instances.put(AchievementsTab.SEARCH, "$S.@ZigSearchRow");
        instances.put(AchievementsTab.CATEGORY, "$C.@DropdownBox");
        instances.put(AchievementsTab.SORT, "$C.@DropdownBox");
        for (Map.Entry<String, String> entry : instances.entrySet()) {
            assertTrue(ui.contains(entry.getValue() + " " + entry.getKey() + " {"),
                    entry.getKey() + " is a " + entry.getValue());
        }
        for (String filled : List.of(AchievementsTab.PAGE, AchievementsTab.PAGE_EMPTY, AchievementsTab.LIST_EMPTY,
                AchievementsTab.EMPTY)) {
            String own = own(block(ui, instances.get(filled) + " " + filled));
            assertTrue(own.contains("FlexWeight: 1;"), filled + " is sized by its parent");
        }
        assertTrue(ui.contains("$ZW = \"../Common/ZigKit.ui\";"));
        assertTrue(ui.contains("$S = \"../Common/ZigSearchRow.ui\";"));
    }

    @Test
    void theTabsOwnButtonsCarryALabel() throws IOException {
        String ui = code(DOCUMENT);
        for (String button : List.of(AchievementsTab.HERO_SHOW, AchievementsTab.MS_COLLECT)) {
            String block = block(ui, "Button " + button);
            assertTrue(block.contains("Label #Label {"), button + " is a Button holding a Label #Label");
            assertTrue(own(block).contains("Style: $ZS.@"), button + " takes a named kit style, which carries Sounds");
        }
        assertFalse(ui.contains("TextButton"), "a labelled button is a Button with a Label");
        assertFalse(ui.contains("ButtonStyle("), "no local button style: the kit's carry their sounds");
    }

    @Test
    void theToolbarListAndPageSpellTheLayout() throws IOException {
        String ui = code(DOCUMENT);
        assertTrue(own(block(ui, "Group " + AchievementsTab.ROOT)).contains(
                "Anchor: (Height: " + LedgerLayout.BODY_HEIGHT + ");"), "the tab fills the shell's tab body");
        String toolbarRow = "Anchor: (Height: " + LedgerLayout.TOOLBAR_HEIGHT + ", Bottom: " + LedgerLayout.TOOLBAR_GAP
                + ");";
        String toolbar = own(block(ui, "Group " + AchievementsTab.TOOLBAR));
        assertTrue(toolbar.contains("LayoutMode: Top;") && toolbar.contains("Anchor: (Height: "
                + (LedgerLayout.TOOLBAR_HEIGHT + LedgerLayout.TOOLBAR_GAP) + ");"),
                "the view strip, its rule and the gap fill a toolbar row and its gap, so nothing below moves");
        assertTrue(own(block(ui, "Group " + AchievementsTab.TOOLBAR_ROW)).contains(
                "Anchor: (Height: " + ViewTabPainter.HEIGHT + ");"), "the strip is a view tab high");
        String rule = own(block(ui, "Group " + AchievementsTab.VIEWS_RULE));
        assertTrue(rule.contains("Anchor: (Height: " + AchievementsTab.RULE_HEIGHT + ");")
                && rule.contains("Background: (Color: $ZK.@ZigDivider);"), "a thin line under the strip");
        String filters = own(block(ui, "Group " + AchievementsTab.FILTERS));
        assertTrue(filters.contains(toolbarRow), "the filter row is a toolbar row too");
        assertTrue(filters.contains("Visible: false;"), "Browse shows the filter row");

        assertTrue(own(block(ui, "Group " + AchievementsTab.LIST_COLUMN)).contains(
                "Anchor: (Width: " + LedgerLayout.LIST_WIDTH + ");"));
        String list = own(block(ui, "Group " + AchievementsTab.LIST));
        assertTrue(list.contains("LayoutMode: TopScrolling;"));
        assertTrue(list.contains("Padding: (Right: " + LedgerLayout.SCROLL_GUTTER + ");"));
        assertTrue(own(block(ui, "Group " + AchievementsTab.SPLIT_GUTTER)).contains(
                "Anchor: (Width: " + LedgerLayout.GUTTER + ");"));
        assertTrue(own(block(ui, "Group " + AchievementsTab.PAGE_CARD)).contains(
                "Anchor: (Width: " + LedgerLayout.PAGE_WIDTH + ");"));

        assertTrue(own(block(ui, "Group " + AchievementsTab.VIEWS)).contains(
                "Anchor: (Width: " + AchievementsTab.VIEWS_WIDTH + ");"));
        assertTrue(own(block(ui, "Group " + AchievementsTab.STATUSES)).contains(
                "Anchor: (Width: " + AchievementsTab.STATUSES_WIDTH + ");"));
        assertTrue(block(ui, "$S.@ZigSearchRow " + AchievementsTab.SEARCH).contains(
                "@Anchor = (Width: " + AchievementsTab.SEARCH_WIDTH + ", Top: " + AchievementsTab.SEARCH_TOP + ");"),
                "the search row sits centred on the strip");
        assertTrue(own(block(ui, "$C.@DropdownBox " + AchievementsTab.CATEGORY)).contains(
                "Anchor: (Width: " + AchievementsTab.CATEGORY_WIDTH + ", Height: " + LedgerLayout.TOOLBAR_HEIGHT + ");"));
        assertTrue(own(block(ui, "$C.@DropdownBox " + AchievementsTab.SORT)).contains(
                "Anchor: (Width: " + AchievementsTab.SORT_WIDTH + ", Height: " + LedgerLayout.TOOLBAR_HEIGHT + ");"));
    }

    @Test
    void theToolbarRowsFitTheBody() {
        assertEquals(3 * ViewTabPainter.STEP, AchievementsTab.VIEWS_WIDTH, "room for every view tab");
        assertTrue(ViewTabPainter.HEIGHT + AchievementsTab.RULE_HEIGHT
                <= LedgerLayout.TOOLBAR_HEIGHT + LedgerLayout.TOOLBAR_GAP, "the strip and its rule fit the row");
        assertEquals((ViewTabPainter.HEIGHT - LedgerLayout.TOOLBAR_HEIGHT) / 2, AchievementsTab.SEARCH_TOP,
                "the 32-high search row centred on the strip");
        assertEquals(BrowseFilter.STATUSES.size() * AchievementsTab.SEGMENT_STEP, AchievementsTab.STATUSES_WIDTH,
                "room for every status");
        assertTrue(AchievementsTab.VIEWS_WIDTH + AchievementsTab.SEARCH_WIDTH <= LedgerLayout.INNER_WIDTH);
        assertTrue(AchievementsTab.STATUSES_WIDTH + AchievementsTab.CATEGORY_WIDTH + AchievementsTab.TOOL_GAP
                + AchievementsTab.SORT_WIDTH <= LedgerLayout.INNER_WIDTH);
    }

    @Test
    void theOverviewSpellsItsCardsStripsAndTiles() throws IOException {
        String ui = code(DOCUMENT);
        String overview = own(block(ui, "Group " + AchievementsTab.OVERVIEW));
        assertTrue(overview.contains("LayoutMode: TopScrolling;"), "the overview scrolls");
        assertTrue(overview.contains("Padding: (Right: " + LedgerLayout.SCROLL_GUTTER + ");"));

        assertTrue(own(block(ui, "Group " + AchievementsTab.HERO)).contains("Anchor: (Width: "
                + AchievementsTab.HERO_WIDTH + ", Height: " + AchievementsTab.CARD_HEIGHT + ");"));
        assertTrue(own(block(ui, "Group " + AchievementsTab.CARD_GAP_ID)).contains(
                "Anchor: (Width: " + AchievementsTab.CARD_GAP + ");"));
        String milestone = block(ui, "Group " + AchievementsTab.MILESTONE);
        assertTrue(own(milestone).contains("Anchor: (Width: " + AchievementsTab.MILESTONE_WIDTH + ", Height: "
                + AchievementsTab.CARD_HEIGHT + ");"));
        assertTrue(own(block(milestone, "Group " + AchievementsTab.TRACK)).contains("Visible: false;"),
                "the milestone card holds a hidden track host for its rung markers");

        for (String strip : List.of(AchievementsTab.PINNED_STRIP, AchievementsTab.RECENT_STRIP,
                AchievementsTab.NEARLY_STRIP)) {
            assertTrue(own(block(ui, "Group " + strip)).contains("Anchor: (Width: " + AchievementsTab.STRIP_WIDTH
                    + ");"), strip + " is a strip column");
        }
        for (String gap : List.of(AchievementsTab.STRIP_GAP_ONE, AchievementsTab.STRIP_GAP_TWO)) {
            assertTrue(own(block(ui, "Group " + gap)).contains("Anchor: (Width: " + AchievementsTab.STRIP_GAP + ");"));
        }

        assertTrue(own(block(ui, "Group " + AchievementsTab.TILES)).contains(
                "LayoutMode: " + AchievementsTab.TILE_LAYOUT + ";"), "the tile grid's layout is the tab's one switch");
        assertTrue(Set.of("LeftWrap", "LeftCenterWrap").contains(AchievementsTab.TILE_LAYOUT));
    }

    @Test
    void theOverviewFillsItsScrollingWidth() {
        assertEquals(LedgerLayout.INNER_WIDTH - LedgerLayout.SCROLL_GUTTER, AchievementsTab.OVERVIEW_WIDTH);
        assertEquals(AchievementsTab.OVERVIEW_WIDTH, AchievementsTab.HERO_WIDTH + AchievementsTab.CARD_GAP
                + AchievementsTab.MILESTONE_WIDTH, "the hero and the milestone card share a row");
        assertEquals(AchievementsTab.OVERVIEW_WIDTH, 3 * AchievementsTab.STRIP_WIDTH + 2 * AchievementsTab.STRIP_GAP,
                "three strips share a row");
        assertTrue(AchievementsTab.TILES_PER_ROW * AchievementsTab.TILE_STEP <= AchievementsTab.OVERVIEW_WIDTH,
                "six category tiles to a row");
    }

    @Test
    void theDocumentKeepsToTheClientsGrammar() throws IOException {
        String ui = code(DOCUMENT);
        Matcher ids = Pattern.compile("#([A-Za-z0-9_]+)").matcher(ui);
        while (ids.find()) {
            assertTrue(ids.group(1).matches("[A-Za-z][A-Za-z0-9]*"), "an id is a letter then letters or digits: "
                    + ids.group(1));
        }
        Matcher modes = Pattern.compile("LayoutMode: (\\w+);").matcher(ui);
        while (modes.find()) {
            assertTrue(LAYOUT_MODES.contains(modes.group(1)), "a vanilla layout mode: " + modes.group(1));
        }
        Matcher alignments = Pattern.compile("(?:Horizontal|Vertical)Alignment: (\\w+)").matcher(ui);
        while (alignments.find()) {
            assertTrue(Set.of("Start", "Center", "End").contains(alignments.group(1)),
                    "an alignment the client reads: " + alignments.group(1));
        }
        assertFalse(ui.contains("ItemIcon"), "pictures are AssetImage slots, never an item icon widget");
        assertFalse(Pattern.compile("FontSize: \\d").matcher(ui).find(), "sizes come from the named text styles");
        Matcher keys = Pattern.compile("%([^;\\s]*);?").matcher(ui);
        String english = Files.readString(ENGLISH, StandardCharsets.UTF_8);
        while (keys.find()) {
            String key = keys.group(1);
            assertTrue(key.matches("[A-Za-z0-9.]+"), "an inline lang key takes letters, digits and dots: " + key);
            assertTrue(key.startsWith(PROGRESSION), "the only inline key is the book's own: " + key);
            String bare = key.substring(PROGRESSION.length());
            assertTrue(english.lines().anyMatch(line -> line.startsWith(bare + " =")), "en-US ships " + key);
        }
    }

    @Test
    void everyKitNameTheDocumentReadsResolves() throws IOException {
        String ui = code(DOCUMENT);
        Map<String, Path> imports = new LinkedHashMap<>();
        Matcher declared = Pattern.compile("(?m)^\\$(\\w+) = \"([^\"]+)\";").matcher(ui);
        while (declared.find()) {
            String path = declared.group(2);
            if (path.startsWith("../Common/")) {
                imports.put(declared.group(1), KIT_COMMON.resolve(path.substring("../Common/".length())));
            } else {
                imports.put(declared.group(1), null);
            }
        }
        Matcher used = Pattern.compile("\\$(\\w+)\\.@(\\w+)").matcher(ui);
        while (used.find()) {
            String alias = used.group(1);
            assertTrue(imports.containsKey(alias), "$" + alias + " is imported");
            Path target = imports.get(alias);
            if (target == null) {
                continue;
            }
            assertTrue(Files.exists(target), target + " exists");
            String defined = code(target);
            assertTrue(Pattern.compile("@" + used.group(2) + "\\s*=").matcher(defined).find(),
                    target.getFileName() + " defines @" + used.group(2));
        }
    }

    /** A document with every {@code //} comment removed. */
    @Nonnull
    static String code(@Nonnull Path file) throws IOException {
        StringBuilder out = new StringBuilder();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            int at = line.indexOf("//");
            out.append(at < 0 ? line : line.substring(0, at)).append('\n');
        }
        return out.toString();
    }

    /** The block {@code declaration} opens. */
    @Nonnull
    static String block(@Nonnull String ui, @Nonnull String declaration) {
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
    static String own(@Nonnull String block) {
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
