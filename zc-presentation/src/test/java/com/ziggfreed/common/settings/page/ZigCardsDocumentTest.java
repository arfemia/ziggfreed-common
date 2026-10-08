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
 * with the numbers {@link SettingsLayout} sizes the tile grid from, under a title row that hosts a consumer's
 * right-mode branding.
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

    /** The block element {@code id} declares, braces included. */
    @Nonnull
    private static String block(@Nonnull String ui, @Nonnull String id) {
        Matcher m = Pattern.compile("\\w+\\s+" + Pattern.quote(id) + "\\s*\\{").matcher(ui);
        assertTrue(m.find(), "declares " + id);
        int depth = 0;
        for (int i = m.end() - 1; i < ui.length(); i++) {
            char c = ui.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return ui.substring(m.end() - 1, i + 1);
            }
        }
        throw new AssertionError(id + " never closes");
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

    /** One property of a block's own, {@code Name: value;}, or null. */
    private static String property(@Nonnull String block, @Nonnull String name) {
        Matcher m = Pattern.compile("(?<![@\\w])" + Pattern.quote(name) + "\\s*:\\s*([^;]+);").matcher(own(block));
        return m.find() ? m.group(1).trim() : null;
    }

    /** An integer leaf of an object value such as an {@code Anchor}; fails when it is not there. */
    private static int leaf(String object, @Nonnull String name) {
        assertNotNull(object, "no object holds " + name);
        Matcher m = Pattern.compile("(?<![\\w@])" + Pattern.quote(name) + "\\s*:\\s*(-?\\d+)").matcher(object);
        assertTrue(m.find(), object + " says " + name);
        return Integer.parseInt(m.group(1));
    }

    private static int count(@Nonnull String ui, @Nonnull String text) {
        int n = 0;
        for (int at = ui.indexOf(text); at >= 0; at = ui.indexOf(text, at + 1)) {
            n++;
        }
        return n;
    }

    /** The width the owner's 260 x 97 logo scales to at {@code height}, rounded to a whole pixel. */
    static int logoWidth(int height) {
        return (int) Math.round(height * 260 / 97.0);
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
        // Sizes are type-scale steps (Common/ZigType.ui): vanilla's section label, its page title, and the
        // description on the readability floor (the maintainer's "Floor 13, body 14", 2026-10-06).
        assertTrue(header.contains("FontSize: $ZT.@ZigFontSection") && header.contains("RenderUppercase: true")
                && header.contains("RenderBold: true") && header.contains("#9aacbc"), header);
        String title = definition(cards, "@ZigPageTitleStyle");
        assertTrue(title.contains("FontSize: $ZT.@ZigFontSubtitle") && title.contains("#d6e4ee"), title);
        String description = definition(cards, "@ZigPageDescriptionStyle");
        assertTrue(description.contains("FontSize: $ZT.@ZigFontCaption") && description.contains("#7f93a6")
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

    /**
     * The Settings tab's title row is a right-mode branding host (M356, M362, M370), so a consumer's painter, told
     * {@code titleRow}, may write each of its four ids: {@code #TitleContainer} holding the owner's logo as ONE
     * scaled {@code AssetImage #BrandingLogo} (hidden, a blank fallback, the logo's 260:97 shape at the row's
     * height) and then {@code #PanelTitle}, the page title in the shared title style; under the row a hidden,
     * wrapping {@code #BrandingDescriptionRight} with no fixed height; then the page's own description. A command
     * against an id the document lacks disconnects the player, so each is declared exactly once, and the retired
     * {@code #SettingsTitle} is gone.
     */
    @Test
    void theSettingsTitleRowDeclaresTheFourBrandingHosts() throws IOException {
        String page = document("Pages/ZigSettingsPage.ui");
        for (String id : List.of("Group #TitleContainer", "AssetImage #BrandingLogo", "Label #PanelTitle",
                "Label #BrandingDescriptionRight", "Label #SettingsDescription", "Group #Sections")) {
            assertEquals(1, count(page, id + " {"), "the Settings page declares " + id + " exactly once");
        }
        assertFalse(page.contains("#SettingsTitle"), "the title is #PanelTitle now, the id the branding painter writes");

        String row = block(page, "#TitleContainer");
        assertTrue(own(row).contains("LayoutMode: Left"), "the logo and the title side by side");
        String rowAnchor = property(row, "Anchor");
        int height = leaf(rowAnchor, "Height");
        assertTrue(leaf(rowAnchor, "Bottom") >= 0, "the row keeps a bottom margin: " + rowAnchor);

        String logo = block(row, "#BrandingLogo");
        assertTrue(row.indexOf("#BrandingLogo {") < row.indexOf("#PanelTitle {"), "the logo comes before the title");
        assertEquals("false", property(logo, "Visible"), "the logo ships hidden until a painter shows it");
        assertEquals("\"UI/Custom/Common/Glyphs/Blank.png\"", property(logo, "FallbackTexturePath"),
                "an owner with no logo draws nothing, not a red X");
        String logoAnchor = property(logo, "Anchor");
        assertEquals(height, leaf(logoAnchor, "Height"), "the logo fills the row's height");
        assertEquals(logoWidth(height), leaf(logoAnchor, "Width"), "the logo keeps its 260:97 shape at that height");
        assertTrue(leaf(logoAnchor, "Right") > 0, "a gap between the logo and the title: " + logoAnchor);

        String title = block(row, "#PanelTitle");
        assertEquals("1", property(title, "FlexWeight"), "the title takes the rest of the row");
        assertEquals("$K.@ZigPageTitleStyle", property(title, "Style"), "the page title's own style");
        assertTrue(definition(document("Common/ZigCards.ui"), "@ZigPageTitleStyle").contains("VerticalAlignment: Center"),
                "the title sits centred against the logo");

        String description = block(page, "#BrandingDescriptionRight");
        assertEquals("false", property(description, "Visible"), "the branding description ships hidden");
        String style = property(description, "Style");
        assertNotNull(style, "the branding description has a style");
        assertTrue(style.contains("$K.@ZigPageDescriptionStyle") && !style.contains("Wrap: false")
                || style.contains("$ZT.@ZigFontCaption") && style.contains("Wrap: true"),
                "a caption style that wraps (the page description's, a caption step that wraps): " + style);
        String descriptionAnchor = property(description, "Anchor");
        assertTrue(descriptionAnchor != null && descriptionAnchor.contains("Bottom:"),
                "a bottom margin before the page's description: " + descriptionAnchor);
        assertFalse(descriptionAnchor.contains("Height"), "no fixed height, so its wrapped lines are not clipped");

        int titleRow = page.indexOf("Group #TitleContainer {");
        int brandingLine = page.indexOf("Label #BrandingDescriptionRight {");
        int ownLine = page.indexOf("Label #SettingsDescription {");
        int sections = page.indexOf("Group #Sections {");
        assertTrue(titleRow < brandingLine && brandingLine < ownLine && ownLine < sections,
                "the title row, the branding description, the page's description, then the sections");
        assertFalse(row.contains("#BrandingDescriptionRight"), "the description sits under the row, not in it");
    }

    @Test
    void theTileGridIsSizedFromTheTilesItHolds() {
        assertTrue(SettingsLayout.TILES_PER_ROW >= 1);
        assertEquals(SettingsLayout.TILE_ROW_HEIGHT, SettingsLayout.tileGridHeight(0), "an empty grid keeps one row's room");
        assertEquals(SettingsLayout.TILE_ROW_HEIGHT, SettingsLayout.tileGridHeight(SettingsLayout.TILES_PER_ROW));
        assertEquals(2 * SettingsLayout.TILE_ROW_HEIGHT, SettingsLayout.tileGridHeight(SettingsLayout.TILES_PER_ROW + 1));
    }
}
