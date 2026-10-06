package com.ziggfreed.common.settings.page;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.Nonnull;

import org.junit.jupiter.api.Test;

/**
 * The family's shared card shapes keep the names and the tile tree other pages paint by (another branch
 * moves the admin hub onto them), carry the game's own type, and the Settings documents build on them
 * with the numbers {@link SettingsLayout} sizes the tile grid from.
 */
class ZigCardsDocumentTest {

    private static final List<String> NAMES = List.of("@ZigSectionCard", "@ZigCardHeaderStyle", "@ZigPageTitleStyle",
            "@ZigPageDescriptionStyle", "@ZigTile", "@ZigTileBtnStyle", "@ZigTileIconGridStyle");

    /** A shipped document with its line comments stripped. */
    @Nonnull
    static String document(@Nonnull String path) throws IOException {
        try (InputStream in = ZigCardsDocumentTest.class.getResourceAsStream("/Common/UI/Custom/" + path)) {
            assertNotNull(in, "the classpath ships " + path);
            StringBuilder out = new StringBuilder();
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                int at = line.indexOf("//");
                out.append(at < 0 ? line : line.substring(0, at)).append('\n');
            }
            return out.toString();
        }
    }

    /** The definition of {@code name}: its braces or its parentheses, whichever opens it. */
    @Nonnull
    static String definition(@Nonnull String ui, @Nonnull String name) {
        Matcher m = Pattern.compile(Pattern.quote(name) + "\\s*=\\s*\\w*\\s*([{(])").matcher(ui);
        assertTrue(m.find(), "the document defines " + name);
        char open = m.group(1).charAt(0);
        char close = open == '{' ? '}' : ')';
        int depth = 0;
        for (int i = m.start(1); i < ui.length(); i++) {
            char c = ui.charAt(i);
            if (c == open) {
                depth++;
            } else if (c == close && --depth == 0) {
                return ui.substring(m.start(1), i + 1);
            }
        }
        throw new AssertionError(name + " never closes");
    }

    @Test
    void theSharedCardNamesAreDefined() throws IOException {
        String cards = document("Common/ZigCards.ui");
        for (String name : NAMES) {
            definition(cards, name);
        }
    }

    @Test
    void theTileKeepsTheAdminHubsTreeAndSize() throws IOException {
        String cards = document("Common/ZigCards.ui");
        String tile = definition(cards, "@ZigTile");

        for (String id : List.of("ItemGrid #TileIcon", "Label #TileTitle", "Label #TileSubtitle", "Group #TileAccent")) {
            assertTrue(tile.contains(id), "a tile declares " + id);
        }
        assertTrue(tile.contains("Anchor: (Width: " + SettingsLayout.TILE_WIDTH + ", Height: " + SettingsLayout.TILE_HEIGHT
                + ", Right: " + SettingsLayout.TILE_RIGHT + ", Bottom: " + SettingsLayout.TILE_BOTTOM + ")"));
        assertTrue(tile.contains("Padding: (Full: 10)"));
        assertTrue(tile.contains("Style: @ZigTileBtnStyle"));
        assertTrue(tile.contains("Style: @ZigTileIconGridStyle"));
        assertTrue(definition(cards, "@ZigTileBtnStyle").contains("Sounds: $C.@ButtonSounds"),
                "a custom button style without sounds clicks silently");
    }

    @Test
    void theHeadingAndThePageTypeAreTheGamesOwn() throws IOException {
        String cards = document("Common/ZigCards.ui");

        String header = definition(cards, "@ZigCardHeaderStyle");
        assertTrue(header.contains("FontSize: 13") && header.contains("RenderUppercase: true")
                && header.contains("RenderBold: true") && header.contains("#9aacbc"), header);
        String title = definition(cards, "@ZigPageTitleStyle");
        assertTrue(title.contains("FontSize: 18") && title.contains("#d6e4ee"), title);
        String description = definition(cards, "@ZigPageDescriptionStyle");
        assertTrue(description.contains("FontSize: 12") && description.contains("#7f93a6")
                && description.contains("Wrap: true"), description);
        String card = definition(cards, "@ZigSectionCard");
        assertTrue(card.contains("Padding: (Full: " + SettingsLayout.CARD_PADDING + ")"));
        assertTrue(card.contains("ContainerPanelPatch.png"));
    }

    @Test
    void theSettingsDocumentsBuildOnTheSharedShapes() throws IOException {
        String page = document("Pages/ZigSettingsPage.ui");
        assertTrue(page.contains("$F.@ZigMenuFrame"), "the Settings tab carries the shared rail");
        assertFalse(page.contains("@ZigDecoratedFrame"));
        assertTrue(page.contains("Group #Sections"));
        assertTrue(page.contains("Style: $K.@ZigPageTitleStyle"));
        assertTrue(page.contains("Style: $K.@ZigPageDescriptionStyle"));
        assertTrue(page.contains("Padding: (Left: " + SettingsLayout.PANEL_PADDING_LEFT + ")"));
        assertTrue(page.contains("Padding: (Right: " + SettingsLayout.SECTIONS_PADDING_RIGHT + ")"));

        String section = document("Pages/ZigSettingsSection.ui");
        assertTrue(section.contains("$K.@ZigSectionCard #Card"));
        assertTrue(section.contains("Style: $K.@ZigCardHeaderStyle"));
        assertTrue(section.contains("Group #Rows"));
        assertTrue(section.contains("LayoutMode: LeftCenterWrap"));
        assertTrue(section.contains("Anchor: (Height: " + SettingsLayout.TILE_ROW_HEIGHT + ")"));

        assertTrue(document("Pages/ZigSettingsTile.ui").contains("$K.@ZigTile #ZigSettingsTile"));
    }

    @Test
    void theTileGridIsSizedFromTheTilesItHolds() {
        assertTrue(SettingsLayout.TILES_PER_ROW >= 1);
        assertEquals(SettingsLayout.TILE_ROW_HEIGHT, SettingsLayout.tileGridHeight(0), "an empty grid keeps one row's room");
        assertEquals(SettingsLayout.TILE_ROW_HEIGHT, SettingsLayout.tileGridHeight(SettingsLayout.TILES_PER_ROW));
        assertEquals(2 * SettingsLayout.TILE_ROW_HEIGHT, SettingsLayout.tileGridHeight(SettingsLayout.TILES_PER_ROW + 1));
    }
}
