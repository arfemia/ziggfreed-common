package com.ziggfreed.common.ui.kit;

import static com.ziggfreed.common.ui.kit.KitDocs.APPENDED;
import static com.ziggfreed.common.ui.kit.KitDocs.DOCUMENTS;
import static com.ziggfreed.common.ui.kit.KitDocs.block;
import static com.ziggfreed.common.ui.kit.KitDocs.declarations;
import static com.ziggfreed.common.ui.kit.KitDocs.declares;
import static com.ziggfreed.common.ui.kit.KitDocs.document;
import static com.ziggfreed.common.ui.kit.KitDocs.leaf;
import static com.ziggfreed.common.ui.kit.KitDocs.own;
import static com.ziggfreed.common.ui.kit.KitDocs.parameter;
import static com.ziggfreed.common.ui.kit.KitDocs.property;
import static com.ziggfreed.common.ui.kit.KitDocs.size;
import static com.ziggfreed.common.ui.kit.KitDocs.style;
import static com.ziggfreed.common.ui.kit.KitDocs.template;
import static com.ziggfreed.common.ui.kit.KitDocs.type;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.Nonnull;
import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

/**
 * The kit's documents (plan section 2.8) keep the ids and sizes its painters and every page build on: a command
 * against an id a template lacks disconnects the player, and no server-side check sees it. Beside the ids: every
 * picture slot is the one plain {@code AssetImage #IcoTex} ({@code ItemIcon} drew blank in game; an item grid in a
 * decorative slot shows a tooltip and a rarity square), every text size is a step of {@code Common/ZigType.ui} at
 * the floor or above, every button style clicks with a sound, every layout mode is one vanilla documents use, every
 * texture a document names ships (here or in the game), and the generated glyphs and hero textures are the sizes
 * the documents draw them at.
 */
class ZigKitDocumentsTest {

    /** The layout modes vanilla's own documents use (server assets and the client's interface). */
    private static final Set<String> LAYOUT_MODES = Set.of("Top", "Bottom", "Left", "Right", "Center", "Middle",
            "CenterMiddle", "MiddleCenter", "Full", "TopScrolling", "BottomScrolling", "LeftCenterWrap", "LeftWrap");

    /** The templates {@code Common/ZigKit.ui} defines. */
    private static final List<String> KIT_TEMPLATES = List.of("@ZigSegment", "@ZigStat", "@ZigPill",
            "@ZigProgressBar", "@ZigProgressBlock", "@ZigSectionHeader", "@ZigPicture", "@ZigDetailPage",
            "@ZigActionBar", "@ZigEmptyState", "@ZigHeroPlate");

    /** The glyph set ({@code Common/Glyphs/}), white on transparent. */
    private static final List<String> GLYPHS = List.of("Pin", "PinFilled", "Lock", "ChevronRight", "ChevronDown",
            "Dot", "Star", "Blank");

    /** The vanilla textures the kit draws, by their path under {@code Common/UI/Custom/}: the game ships them. */
    private static final Set<String> VANILLA_TEXTURES = Set.of("Common/ProgressBar.png", "Common/ProgressBarFill.png",
            "Common/Checkmark.png", "Pages/Memories/Checkmark.png", "Pages/Memories/MissingIcon.png",
            "Pages/Memories/Tiles/TileDefault.png", "Pages/Memories/Tiles/TileHovered.png",
            "Pages/Memories/Tiles/TileComplete.png", "Pages/Memories/Tiles/TileEmpty.png",
            "Pages/Memories/Tiles/NewMemoryIndicator.png", "Common/Buttons/Primary_Square.png",
            "Common/Buttons/Primary_Square_Hovered.png", "Common/Buttons/Primary_Square_Pressed.png",
            "Common/Buttons/Secondary.png", "Common/Buttons/Secondary_Hovered.png",
            "Common/Buttons/Secondary_Pressed.png", "Common/Buttons/Destructive.png",
            "Common/Buttons/Destructive_Hovered.png", "Common/Buttons/Destructive_Pressed.png",
            "Common/Buttons/Disabled.png");

    private static final Pattern FONT_SIZE = Pattern.compile("(?:MinShrinkTextToFitFontSize|FontSize)\\s*:\\s*([^,;)]+)");
    private static final Pattern STEP = Pattern.compile("\\$ZT\\.@ZigFont([A-Za-z]+)");
    private static final Pattern TEXTURE = Pattern.compile(
            "(?:TexturePath|BarTexturePath|FallbackTexturePath|Background)\\s*:\\s*\"([^\"]+)\"");

    // ---- the appended templates (each a Group root named after its file, the 2.8 ids inside it) ----

    @Test
    void everyAppendedTemplateIsAGroupNamedAfterItsFile() throws IOException {
        for (String path : APPENDED) {
            String ui = document(path);
            String root = "#" + path.substring(path.indexOf('/') + 1, path.length() - ".ui".length());
            assertEquals("Group", type(ui, root), path + " is rooted at Group " + root);
            assertEquals(1, declarations(ui, root), path + " declares its root once");
            List<Integer> tops = topLevelBraces(ui);
            assertEquals(1, tops.size(), path + " has one root element and no templates of its own");
            assertTrue(ui.substring(0, tops.get(0)).trim().endsWith("Group " + root),
                    path + ": everything sits inside " + root + ", so list[i] + \" #Id\" reaches each id");
        }
    }

    @Test
    void theNamedTextStylesAreThePlansScale() throws IOException {
        String text = document("Common/ZigText.ui");
        // name -> step, ink, and what else the style must say
        Map<String, List<String>> styles = new LinkedHashMap<>();
        styles.put("@ZigCaptionStyle", List.of("Caption", "ZigInkMuted"));
        styles.put("@ZigFaintStyle", List.of("Caption", "ZigInkFaint"));
        styles.put("@ZigCountStyle", List.of("Caption", "ZigInkBody", "RenderBold: true", "HorizontalAlignment: End"));
        styles.put("@ZigSectionLabelStyle", List.of("Section", "ZigInkSection", "RenderBold: true",
                "RenderUppercase: true", "LetterSpacing: 1"));
        styles.put("@ZigBodyStyle", List.of("Body", "ZigInkBody"));
        styles.put("@ZigButtonLabelStyle", List.of("Body", "ZigInkBright", "RenderBold: true", "ShrinkTextToFit: true",
                "MinShrinkTextToFitFontSize: $ZT.@ZigFontCaption"));
        styles.put("@ZigRowTitleStyle", List.of("Emphasis", "ZigInkStrong", "RenderBold: true", "Wrap: true",
                "WrapMaxLines: 1"));
        styles.put("@ZigTileNameStyle", List.of("Emphasis", "ZigInkStrong", "RenderBold: true",
                "FontName: \"Secondary\"", "RenderUppercase: true", "WrapMaxLines: 2"));
        styles.put("@ZigHeadingStyle", List.of("Heading", "ZigInkStrong", "RenderBold: true"));
        styles.put("@ZigLeadStyle", List.of("Heading", "ZigInkBody", "Wrap: true"));
        styles.put("@ZigSubtitleStyle", List.of("Subtitle", "ZigInkStrong", "RenderBold: true"));
        styles.put("@ZigTitleStyle", List.of("Title", "ZigInkStrong", "RenderBold: true", "WrapMaxLines: 2"));
        styles.put("@ZigFigureStyle", List.of("Display", "ZigInkStrong", "RenderBold: true", "ShrinkTextToFit: true",
                "MinShrinkTextToFitFontSize: $ZT.@ZigFontSubtitle"));
        styles.put("@ZigHeroTitleStyle", List.of("Hero", "ZigInkBright", "RenderBold: true", "FontName: \"Secondary\"",
                "RenderUppercase: true", "ShrinkTextToFit: true", "MinShrinkTextToFitFontSize: $ZT.@ZigFontTitle"));
        for (Map.Entry<String, List<String>> entry : styles.entrySet()) {
            String body = style(text, entry.getKey());
            List<String> spec = entry.getValue();
            assertTrue(body.contains("FontSize: $ZT.@ZigFont" + spec.get(0) + ","), entry.getKey() + " is the "
                    + spec.get(0) + " step: " + body);
            assertTrue(body.contains("TextColor: $ZK.@" + spec.get(1)), entry.getKey() + " is in " + spec.get(1));
            for (String also : spec.subList(2, spec.size())) {
                assertTrue(body.contains(also), entry.getKey() + " says " + also + ": " + body);
            }
        }
    }

    @Test
    void theLedgerRowCarriesTheSeamIdsInsideOneButton() throws IOException {
        String ui = document("Pages/ZigLedgerRow.ui");
        assertEquals(56, size(leaf(property(block(ui, "#ZigLedgerRow"), "Anchor"), "Height")), "a row is 56 high");
        assertNull(leaf(property(block(ui, "#ZigLedgerRow"), "Anchor"), "Width"), "a row fills its parent's width");
        assertLedgerRow(ui, 32);
        String select = block(ui, "#Select");
        assertTrue(declares(select, "#Meta"), "the standard row's second line");
        assertOneLine(property(block(ui, "#Meta"), "Style"), "$ZX.@ZigCaptionStyle");
        assertEquals("$ZX.@ZigRowTitleStyle", property(block(ui, "#Title"), "Style"));
    }

    @Test
    void theCompactRowHasTheSameIdsWithoutAMetaLine() throws IOException {
        String ui = document("Pages/ZigLedgerRowCompact.ui");
        assertEquals(44, size(leaf(property(block(ui, "#ZigLedgerRowCompact"), "Anchor"), "Height")),
                "a compact row is 44 high");
        assertLedgerRow(ui, 28);
        assertFalse(declares(ui, "#Meta"), "a compact row has no second line");
    }

    @Test
    void aSectionIsAHeadButtonOverItsRows() throws IOException {
        String ui = document("Pages/ZigLedgerSection.ui");
        String section = block(ui, "#Section");
        assertEquals("Group", type(ui, "#Section"));
        assertEquals("Button", type(section, "#Head"));
        String head = block(section, "#Head");
        assertEquals(32, size(leaf(property(head, "Anchor"), "Height")), "the head is 32 high");
        assertStyled(head);
        assertGlyphHolder(head, "#Chevron", 16, Map.of("#Open", "ChevronDown", "#Closed", "ChevronRight"));
        assertEquals("$ZX.@ZigSectionLabelStyle", property(block(head, "#Label"), "Style"));
        assertEquals("Label", type(head, "#Count"));
        assertEquals("Group", type(section, "#Rows"));
        assertEquals("Top", property(block(section, "#Rows"), "LayoutMode"), "rows stack under the head");
        assertTrue(section.indexOf("#Head") < section.indexOf("#Rows"), "the head sits above its rows");
    }

    @Test
    void showMoreIsOneLabelledButton() throws IOException {
        String ui = document("Pages/ZigShowMoreRow.ui");
        assertEquals(32, size(leaf(property(block(ui, "#ZigShowMoreRow"), "Anchor"), "Height")));
        assertEquals("Button", type(ui, "#More"));
        assertStyled(block(ui, "#More"));
        assertEquals("Label", type(block(ui, "#More"), "#Label"));
    }

    @Test
    void aDetailBlockIsASectionHeaderOverItsLines() throws IOException {
        String ui = document("Pages/ZigDetailBlock.ui");
        String blockGroup = block(ui, "#Block");
        assertEquals("$ZW.@ZigSectionHeader", type(blockGroup, "#Head"));
        assertEquals("Group", type(blockGroup, "#Lines"));
        assertEquals("Top", property(block(blockGroup, "#Lines"), "LayoutMode"));
        assertTrue(blockGroup.indexOf("#Head") < blockGroup.indexOf("#Lines"));
    }

    @Test
    void theDetailLineKeepsItsIdsAndGainsTheKitsSlots() throws IOException {
        String ui = document("Pages/ZigDetailLine.ui");
        String root = block(ui, "#ZigDetailLine");
        assertEquals(32, size(leaf(property(root, "Anchor"), "Height")), "a line is 32 high");
        // Today's callers address these, so they stay exactly as they are.
        assertEquals("Group", type(ui, "#LineIconSlot"));
        assertEquals("ItemGrid", type(ui, "#IcoItem"));
        assertEquals("AssetImage", type(ui, "#IcoTex"));
        assertEquals("Label", type(ui, "#LineText"));
        assertTrue(property(block(ui, "#LineText"), "Style").contains("FontSize: $ZT.@ZigFontHeading"),
                "reading lines stay at the heading step");
        assertTrue(property(block(ui, "#IcoItem"), "Style") != null, "a grid with no Style draws nothing");
        // The kit's additions, all hidden so a line that sets none of them reads as before.
        assertGlyphHolder(ui, "#Tick", 20, Map.of("#Done", "Checkmark", "#Current", "Dot", "#Ahead", "Dot",
                "#Locked", "Lock"));
        assertHidden(ui, "#Tick");
        assertEquals("Label", type(ui, "#Count"));
        assertEquals(96, size(leaf(property(block(ui, "#Count"), "Anchor"), "Width")));
        assertHidden(ui, "#Count");
        assertEquals("$ZW.@ZigPill", type(ui, "#Tag"));
        assertHidden(ui, "#Tag");
        assertEquals("Button", type(ui, "#LineSelect"));
        assertHidden(ui, "#LineSelect");
        assertStyled(block(ui, "#LineSelect"));
        assertEquals("(Full: 0)", property(block(ui, "#LineSelect"), "Anchor"), "the select button covers the line");
        assertTrue(root.indexOf("#LineSelect") < root.indexOf("#LineText"),
                "the select button sits behind the line's content, which draws over it");
    }

    @Test
    void theCollectionTileIsOneButtonOnTheMemoriesTiles() throws IOException {
        String ui = document("Pages/ZigCollectionTile.ui");
        String anchor = property(block(ui, "#ZigCollectionTile"), "Anchor");
        assertEquals(196, size(leaf(anchor, "Width")));
        assertEquals(128, size(leaf(anchor, "Height")));
        assertEquals("Button", type(ui, "#Tile"));
        assertEquals("$ZS.@ZigTileStyle", property(block(ui, "#Tile"), "Style"));
        String tile = block(ui, "#Tile");
        assertPicture(tile, "#Pic", 40);
        assertEquals("$ZX.@ZigTileNameStyle", property(block(tile, "#Name"), "Style"));
        assertEquals("Label", type(tile, "#Count"));
        assertBar(tile, 6);
        assertEquals("Group", type(tile, "#Check"));
        assertHidden(tile, "#Check");
        assertTrue(property(block(tile, "#Check"), "Background").contains("Pages/Memories/Checkmark.png"),
                "the Memories tile's own green check");
        assertEquals("$ZW.@ZigPill", type(tile, "#Badge"));
        assertHidden(tile, "#Badge");
        assertHidden(tile, "#New");
        assertTrue(property(block(tile, "#New"), "Background").contains("NewMemoryIndicator.png"));
        assertEquals("$C.@CircularProgressBar", type(tile, "#Ring"));
        assertHidden(tile, "#Ring");
        assertHidden(tile, "#AccentStrip");
        assertEquals(4, size(leaf(property(block(tile, "#AccentStrip"), "Anchor"), "Height")));
        assertEquals(1, count(ui, "\\bButton\\s+#"), "the tile is the one button");
    }

    @Test
    void theKeepsakeTileSwapsItsLayersNotAStyle() throws IOException {
        String ui = document("Pages/ZigKeepsakeTile.ui");
        String anchor = property(block(ui, "#ZigKeepsakeTile"), "Anchor");
        assertEquals(112, size(leaf(anchor, "Width")));
        assertEquals(148, size(leaf(anchor, "Height")));
        String keep = block(ui, "#Keep");
        assertEquals("Group", type(ui, "#Keep"));
        assertEquals("$C.@DefaultTextTooltipStyle", property(keep, "TextTooltipStyle"),
                "the tooltip Java sets on #Keep draws in the game's tooltip");
        Map<String, String> layers = Map.of("#KeepEarned", "TileComplete.png", "#KeepToEarn", "TileDefault.png",
                "#KeepMissed", "TileEmpty.png");
        for (Map.Entry<String, String> layer : layers.entrySet()) {
            assertTrue(property(block(keep, layer.getKey()), "Background").contains(layer.getValue()),
                    layer.getKey() + " is the Memories " + layer.getValue());
        }
        assertPicture(keep, "#Pic", 64);
        assertHidden(keep, "#Scrim");
        assertEquals("Label", type(keep, "#Label"));
        assertEquals("Label", type(keep, "#StateLine"));
    }

    @Test
    void theStatTileHoldsAFigureANameAndTwoLines() throws IOException {
        String ui = document("Pages/ZigStatTile.ui");
        String anchor = property(block(ui, "#ZigStatTile"), "Anchor");
        assertEquals(216, size(leaf(anchor, "Width")));
        assertEquals(128, size(leaf(anchor, "Height")));
        String tile = block(ui, "#StatTile");
        assertEquals("(TexturePath: \"../Pages/Memories/Tiles/TileDefault.png\", Border: 8)", property(tile, "Background"),
                "every tile in the family sits on vanilla's Memories tile");
        assertPicture(tile, "#Pic", 40);
        assertEquals("$ZX.@ZigFigureStyle", property(block(tile, "#Figure"), "Style"));
        assertTrue(property(block(tile, "#Name"), "Style").contains("WrapMaxLines: 2"), "the name takes two lines");
        assertEquals("Label", type(tile, "#Caption"));
        assertEquals("Label", type(tile, "#Server"));
        assertHidden(tile, "#Server");
    }

    @Test
    void theSegmentDocumentIsTheKitsSegment() throws IOException {
        String ui = document("Pages/ZigSegment.ui");
        assertEquals("$ZW.@ZigSegment", type(ui, "#Seg"));
        String segment = template(document("Common/ZigKit.ui"), "@ZigSegment");
        assertTrue(segment.startsWith("{") && document("Common/ZigKit.ui").contains("@ZigSegment = Button {"),
                "a segment is a Button with an inner #Label, never a TextButton");
        String anchor = property(segment, "Anchor");
        assertEquals(132, size(leaf(anchor, "Width")));
        assertEquals(32, size(leaf(anchor, "Height")));
        assertEquals("$ZS.@ZigSegmentStyle", property(segment, "Style"));
        assertEquals("$ZX.@ZigButtonLabelStyle", property(block(segment, "#Label"), "Style"));
        assertGlyph(segment, "#Check", 16, "Checkmark");
        assertGlyph(segment, "#Dot", 8, "Dot");
    }

    @Test
    void theViewTabIsAPictureALabelAndAGoldBar() throws IOException {
        String ui = document("Pages/ZigViewTab.ui");
        String root = block(ui, "#ZigViewTab");
        assertEquals("Top", property(root, "LayoutMode"), "the tab over its bar");
        assertEquals(ViewTabPainter.WIDTH, size(leaf(property(root, "Anchor"), "Width")));
        assertEquals(ViewTabPainter.HEIGHT, size(leaf(property(root, "Anchor"), "Height")));
        assertEquals("Button", type(ui, "#Tab"), "a tab is a Button with an inner #Label, never a TextButton");
        String tab = block(ui, "#Tab");
        assertEquals("$ZS.@ZigViewTabStyle", property(tab, "Style"));
        assertEquals(ViewTabPainter.HEIGHT - ViewTabPainter.BAR, size(leaf(property(tab, "Anchor"), "Height")));
        assertEquals("$ZW.@ZigPicture", type(ui, "#Pic"), "the picture is the kit's plain slot");
        assertEquals("$ZK.@ZigPicLine", parameter(block(ui, "#Pic"), "Size"), "a 28px picture, as the rail's");
        assertEquals("$ZS.@ZigViewTabLabelStyle", property(block(ui, "#Label"), "Style"));
        String bar = block(ui, "#Bar");
        assertEquals(ViewTabPainter.BAR, size(leaf(property(bar, "Anchor"), "Height")));
        assertEquals("$ZK.@ZigAccent", leaf(property(bar, "Background"), "Color"), "the rail's gold");
        assertEquals("false", property(bar, "Visible"), "only the chosen tab shows its bar");
    }

    @Test
    void thePillDocumentIsTheKitsPill() throws IOException {
        String ui = document("Pages/ZigPill.ui");
        assertEquals("$ZW.@ZigPill", type(ui, "#Pill"));
        String pill = template(document("Common/ZigKit.ui"), "@ZigPill");
        assertEquals(24, size(leaf(property(pill, "Anchor"), "Height")), "a pill is 24 high");
        assertNull(leaf(property(pill, "Anchor"), "Width"), "a pill is as wide as its words");
        assertEquals("(Color: $ZK.@ZigSurfaceScrim)", property(pill, "Background"));
        assertGlyph(pill, "#Dot", 8, "Dot");
        assertEquals("$ZS.@ZigStateStyle", property(block(pill, "#Label"), "Style"), "a pill's word is a state word");
    }

    // ---- the kit's own templates (Common/ZigKit.ui) ----

    @Test
    void theKitDefinesEveryTemplate() throws IOException {
        String kit = document("Common/ZigKit.ui");
        for (String name : KIT_TEMPLATES) {
            template(kit, name);
        }
    }

    @Test
    void thePictureIsOnePlainAssetImage() throws IOException {
        String picture = template(document("Common/ZigKit.ui"), "@ZigPicture");
        assertEquals("AssetImage", type(picture, "#IcoTex"));
        String image = block(picture, "#IcoTex");
        assertEquals("\"UI/Custom/Common/Glyphs/Blank.png\"", property(image, "FallbackTexturePath"),
                "an unknown picture draws nothing, not a red X");
        assertEquals("false", property(image, "Visible"), "a slot nothing painted shows nothing");
        assertFalse(picture.contains("ItemGrid") || picture.contains("ItemIcon"));
    }

    @Test
    void theStatAndTheSectionHeaderKeepTheirIds() throws IOException {
        String kit = document("Common/ZigKit.ui");
        String stat = template(kit, "@ZigStat");
        assertEquals(150, size(leaf(property(stat, "Anchor"), "Width")));
        assertEquals(60, size(leaf(property(stat, "Anchor"), "Height")));
        assertTrue(property(block(stat, "#Value"), "Style").contains("$ZX.@ZigFigureStyle"));
        assertTrue(property(block(stat, "#Label"), "Style").contains("$ZX.@ZigCaptionStyle"));

        String header = template(kit, "@ZigSectionHeader");
        assertEquals(32, size(leaf(property(header, "Anchor"), "Height")));
        assertEquals("Label", type(header, "#HeadLabel"));
        assertEquals("$ZX.@ZigSectionLabelStyle", property(block(header, "#HeadLabel"), "Style"));
        assertTrue(property(block(header, "#Meta"), "Style").contains("$ZX.@ZigCaptionStyle"));
        assertEquals("Button", type(header, "#Link"));
        assertHidden(header, "#Link");
        assertEquals("Label", type(block(header, "#Link"), "#Label"));
        assertEquals(1, declarations(header, "#Label"),
                "the link's label is the header's only #Label, so \"#Head #Label\" matches one element");
        assertEquals("(Color: $ZK.@ZigDivider)", property(block(header, "#Rule"), "Background"), "a divider rule");
        assertEquals(1, size(leaf(property(block(header, "#Rule"), "Anchor"), "Height")), "1px");
    }

    @Test
    void theProgressBarAndBlockComposeVanillasBar() throws IOException {
        String kit = document("Common/ZigKit.ui");
        String bar = template(kit, "@ZigProgressBar");
        assertNotNull(parameter(bar, "Height"), "the bar's height is a parameter (4, 6 or 8)");
        assertEquals("\"../Common/ProgressBar.png\"", property(bar, "Background"));
        assertEquals("ProgressBar", type(bar, "#Bar"));
        assertEquals("\"../Common/ProgressBarFill.png\"", property(block(bar, "#Bar"), "BarTexturePath"));

        String progress = template(kit, "@ZigProgressBlock");
        assertEquals("@ZigProgressBar", type(progress, "#BarTrack"));
        assertEquals(8, size(parameter(block(progress, "#BarTrack"), "Height")), "the page's bar is 8 high");
        assertTrue(property(block(progress, "#Count"), "Style").contains("RenderBold: true"));
        assertTrue(property(block(progress, "#Percent"), "Style").contains("$ZX.@ZigCaptionStyle"));
    }

    @Test
    void theDetailPageAndItsActionBarKeepTheirIds() throws IOException {
        String kit = document("Common/ZigKit.ui");
        String page = template(kit, "@ZigDetailPage");
        String header = block(page, "#DHeader");
        assertEquals("@ZigPicture", type(header, "#DPic"));
        assertEquals(48, size(parameter(block(header, "#DPic"), "Size")));
        assertTrue(property(block(header, "#DTitle"), "Style").contains("$ZX.@ZigTitleStyle"));
        assertEquals("Label", type(header, "#DMeta"));
        assertTrue(property(block(header, "#DSubMeta"), "Style").contains("$ZX.@ZigFaintStyle"));
        assertEquals("Left", property(block(header, "#DBadges"), "LayoutMode"));
        assertEquals("Button", type(header, "#DToggle"));
        assertHidden(header, "#DToggle");
        assertEquals("Label", type(block(header, "#DToggle"), "#Label"));
        assertEquals("@ZigProgressBlock", type(page, "#DProgress"));
        assertEquals("$ZX.@ZigLeadStyle", property(block(page, "#DLead"), "Style"));
        assertEquals("TopScrolling", property(block(page, "#DBlocks"), "LayoutMode"));
        assertEquals("@ZigActionBar", type(page, "#DActions"));
        assertTrue(page.indexOf("#DHeader") < page.indexOf("#DProgress") && page.indexOf("#DProgress")
                < page.indexOf("#DLead") && page.indexOf("#DLead") < page.indexOf("#DBlocks")
                && page.indexOf("#DBlocks") < page.indexOf("#DActions"), "header, progress, lead, blocks, actions");

        String bar = template(kit, "@ZigActionBar");
        assertEquals(44, size(leaf(property(bar, "Anchor"), "Height")), "the action bar is 44 high");
        assertTrue(bar.indexOf("#ActionRule") < bar.indexOf("#Hint"), "a rule above");
        assertTrue(property(block(bar, "#Hint"), "Style").contains("WrapMaxLines: 2"), "a hint takes two lines");
        Map<String, Integer> widths = new LinkedHashMap<>();
        widths.put("#Danger", 120);
        widths.put("#Secondary", 140);
        widths.put("#Primary", 160);
        int last = -1;
        for (Map.Entry<String, Integer> button : widths.entrySet()) {
            String b = block(bar, button.getKey());
            assertEquals("Button", type(bar, button.getKey()));
            assertEquals(button.getValue(), size(leaf(property(b, "Anchor"), "Width")), button.getKey());
            assertEquals(40, size(leaf(property(b, "Anchor"), "Height")), button.getKey() + " is 40 high");
            assertEquals("Label", type(b, "#Label"));
            assertEquals("$ZX.@ZigButtonLabelStyle", property(block(b, "#Label"), "Style"));
            assertHidden(bar, button.getKey());
            assertTrue(bar.indexOf(button.getKey() + " {") > last, "Danger, then Secondary, then Primary");
            last = bar.indexOf(button.getKey() + " {");
        }
        assertEquals("$ZS.@ZigButtonDangerStyle", property(block(bar, "#Danger"), "Style"));
        assertEquals("$ZS.@ZigButtonSecondaryStyle", property(block(bar, "#Secondary"), "Style"));
        assertEquals("$ZS.@ZigButtonPrimaryStyle", property(block(bar, "#Primary"), "Style"));
    }

    @Test
    void theEmptyStateCentresAPictureATitleALineAndAnAction() throws IOException {
        String empty = template(document("Common/ZigKit.ui"), "@ZigEmptyState");
        assertEquals("MiddleCenter", property(empty, "LayoutMode"));
        assertEquals("@ZigPicture", type(empty, "#EPic"));
        assertEquals(64, size(parameter(block(empty, "#EPic"), "Size")));
        assertTrue(property(block(empty, "#ETitle"), "Style").contains("$ZX.@ZigSubtitleStyle"));
        assertEquals(440, size(leaf(property(block(empty, "#ELine"), "Anchor"), "MaxWidth")));
        assertTrue(property(block(empty, "#ELine"), "Style").contains("Wrap: true"));
        assertEquals("Button", type(empty, "#EAction"));
        assertHidden(empty, "#EAction");
        assertEquals("Label", type(block(empty, "#EAction"), "#Label"));
    }

    @Test
    void theHeroPlateStacksArtFadeTextAndAccent() throws IOException {
        String hero = template(document("Common/ZigKit.ui"), "@ZigHeroPlate");
        assertEquals(962, size(leaf(property(hero, "Anchor"), "Width")));
        assertEquals(240, size(leaf(property(hero, "Anchor"), "Height")));
        assertEquals("Full", property(hero, "LayoutMode"), "its children stack, each placed by its own anchor");
        assertEquals("AssetImage", type(hero, "#HeroArt"));
        assertEquals("\"UI/Custom/Common/ZigHeroPlate.png\"", property(block(hero, "#HeroArt"), "FallbackTexturePath"),
                "no art draws the plate");
        String fade = property(block(hero, "#HeroFade"), "Anchor");
        assertEquals(140, size(leaf(fade, "Height")));
        assertEquals(0, size(leaf(fade, "Bottom")), "the fade sits on the plate's bottom edge");
        assertEquals("\"../Common/ZigHeroFade.png\"", property(block(hero, "#HeroFade"), "Background"));
        assertEquals("@ZigPicture", type(hero, "#HeroPic"));
        assertEquals(64, size(parameter(block(hero, "#HeroPic"), "Size")));
        assertHidden(hero, "#HeroPic");
        String text = property(block(hero, "#HeroText"), "Anchor");
        assertEquals(28, size(leaf(text, "Left")));
        assertEquals(24, size(leaf(text, "Bottom")));
        assertEquals("@ZigPill", type(hero, "#HeroChip"));
        assertEquals("$ZX.@ZigHeroTitleStyle", property(block(hero, "#HeroTitle"), "Style"));
        assertEquals("Label", type(hero, "#HeroDates"));
        assertEquals(4, size(leaf(property(block(hero, "#HeroAccent"), "Anchor"), "Height")));
        assertTrue(hero.indexOf("#HeroArt") < hero.indexOf("#HeroFade") && hero.indexOf("#HeroFade")
                < hero.indexOf("#HeroText"), "art, then the fade over it, then the words over both");
        // The composed hero's two tintable layers, over the plate and under the picture.
        Map<String, String> layers = Map.of("#HeroGradient", "Kit/HeroGradient.png", "#HeroGlow", "Kit/HeroGlow.png");
        for (Map.Entry<String, String> layer : layers.entrySet()) {
            String block = block(hero, layer.getKey());
            assertEquals("Group", type(hero, layer.getKey()));
            assertEquals("false", property(block, "Visible"), layer.getKey() + " ships hidden");
            assertTintable(layer.getKey(), property(block, "Background"),
                    layer.getValue().substring("Kit/".length(), layer.getValue().length() - ".png".length()));
            assertTrue(property(block, "Background").contains("\"../Common/" + layer.getValue() + "\""),
                    layer.getKey() + " draws " + layer.getValue());
            assertTrue(hero.indexOf("#HeroArt") < hero.indexOf(layer.getKey())
                    && hero.indexOf(layer.getKey()) < hero.indexOf("#HeroPic"), layer.getKey()
                    + " lies over the plate and under the picture");
        }
        String gradient = property(block(hero, "#HeroGradient"), "Anchor");
        assertEquals(962, size(leaf(gradient, "Width")), "the gradient spans the plate");
        assertEquals(240, size(leaf(gradient, "Height")), "the gradient spans the plate");
    }

    @Test
    void theHeroPlateDrawsItsOwnPlateAndHostsTheComposedItemsUnderTheFade() throws IOException {
        String hero = template(document("Common/ZigKit.ui"), "@ZigHeroPlate");
        assertEquals("\"../Common/ZigHeroPlate.png\"", property(hero, "Background"),
                "the plate is the hero's own background: an AssetImage with no path drew nothing in game (spike SP14)");
        assertHidden(hero, "#HeroArt");
        assertEquals("Group", type(hero, "#HeroItems"));
        String items = block(hero, "#HeroItems");
        assertEquals("Full", property(items, "LayoutMode"), "each composed item is placed by its own anchor");
        String anchor = property(items, "Anchor");
        assertEquals(962, size(leaf(anchor, "Width")), "the items' host spans the plate");
        assertEquals(240, size(leaf(anchor, "Height")), "the items' host spans the plate");
        assertTrue(hero.indexOf("#HeroGlow") < hero.indexOf("#HeroItems")
                && hero.indexOf("#HeroItems") < hero.indexOf("#HeroFade"), "the items lie over the glow, under the fade");
    }

    @Test
    void everyButtonAPainterGivesATooltipCarriesTheTooltipStyle() throws IOException {
        // A TooltipText with no TextTooltipStyle draws nothing, so the toggle and the action buttons carry vanilla's.
        String kit = document("Common/ZigKit.ui");
        assertEquals("$C.@DefaultTextTooltipStyle",
                property(block(template(kit, "@ZigDetailPage"), "#DToggle"), "TextTooltipStyle"), "#DToggle");
        String bar = template(kit, "@ZigActionBar");
        for (String button : List.of("#Danger", "#Secondary", "#Primary")) {
            assertEquals("$C.@DefaultTextTooltipStyle", property(block(bar, button), "TextTooltipStyle"), button);
        }
        assertEquals("$C.@DefaultTextTooltipStyle",
                property(block(document("Pages/ZigDetailLine.ui"), "#LineIconSlot"), "TextTooltipStyle"),
                "a detail line's picture carries a reward's own words on hover");
    }

    // ---- rules across every kit document ----

    @Test
    void everyPictureSlotIsAPlainAssetImage() throws IOException {
        for (String path : DOCUMENTS) {
            String ui = document(path);
            assertFalse(Pattern.compile("\\bItemIcon\\b").matcher(ui).find(), path + ": an ItemIcon draws blank in game");
            if (!path.equals("Pages/ZigDetailLine.ui")) {
                assertFalse(ui.contains("ItemGrid"), path + ": a decorative picture shows no tooltip and no rarity square");
            }
            Matcher instance = Pattern.compile("(\\$ZW\\.)?@ZigPicture\\s+(#[A-Za-z0-9]+)\\s*\\{").matcher(ui);
            while (instance.find()) {
                assertNotNull(parameter(block(ui, instance.group(2)), "Size"), path + " " + instance.group(2)
                        + " names its rung");
            }
        }
    }

    @Test
    void everyTextSizeIsATypeScaleStepAtTheFloorOrAbove() throws IOException {
        Map<String, Integer> steps = new LinkedHashMap<>();
        Matcher step = Pattern.compile("(?m)^@ZigFont([A-Za-z]+)\\s*=\\s*(\\d+)\\s*;").matcher(document("Common/ZigType.ui"));
        while (step.find()) {
            steps.put(step.group(1), Integer.parseInt(step.group(2)));
        }
        List<String> bad = new ArrayList<>();
        for (String path : DOCUMENTS) {
            Matcher size = FONT_SIZE.matcher(document(path));
            while (size.find()) {
                Matcher named = STEP.matcher(size.group(1).trim());
                if (!named.matches() || !steps.containsKey(named.group(1)) || steps.get(named.group(1)) < 13) {
                    bad.add(path + ": " + size.group());
                }
            }
        }
        assertTrue(bad.isEmpty(), "every size is a Common/ZigType.ui step of 13 or more (never a number, never "
                + "Micro): " + bad);
    }

    @Test
    void everyButtonStyleClicksWithASound() throws IOException {
        List<String> bad = new ArrayList<>();
        for (String path : DOCUMENTS) {
            String ui = document(path);
            Matcher buttonStyle = Pattern.compile("(?m)^(@[A-Za-z0-9]+)\\s*=\\s*ButtonStyle\\(").matcher(ui);
            while (buttonStyle.find()) {
                if (!style(ui, buttonStyle.group(1)).contains("Sounds:")) {
                    bad.add(path + " " + buttonStyle.group(1));
                }
            }
            Matcher button = Pattern.compile("\\bButton\\s+(#[A-Za-z0-9]+)\\s*\\{").matcher(ui);
            while (button.find()) {
                String style = property(block(ui, button.group(1)), "Style");
                if (style == null || !style.startsWith("$ZS.@") && !style.startsWith("@")) {
                    bad.add(path + " " + button.group(1) + " (Style: " + style + ")");
                }
            }
        }
        assertTrue(bad.isEmpty(), "every custom button style carries Sounds and every button takes a named one, or it "
                + "clicks silently: " + bad);
        assertFalse(String.join("", documents()).contains("TextButton"), "a labelled button is a Button + #Label");
    }

    @Test
    void everyLayoutModeIsOneVanillaUses() throws IOException {
        for (String path : DOCUMENTS) {
            Matcher mode = Pattern.compile("LayoutMode\\s*:\\s*([A-Za-z]+)").matcher(document(path));
            while (mode.find()) {
                assertTrue(LAYOUT_MODES.contains(mode.group(1)), path + ": LayoutMode " + mode.group(1));
            }
        }
    }

    @Test
    void noLabelInTheKitCarriesATooltip() throws IOException {
        for (String path : DOCUMENTS) {
            String ui = document(path);
            Matcher label = Pattern.compile("\\bLabel\\s+(#[A-Za-z0-9]+)\\s*\\{").matcher(ui);
            while (label.find()) {
                assertNull(property(block(ui, label.group(1)), "TooltipText"),
                        path + " " + label.group(1) + ": a label with a tooltip swallows its button's click");
            }
        }
    }

    @Test
    void everyTextureADocumentNamesShips() throws IOException {
        List<String> bad = new ArrayList<>();
        for (String path : DOCUMENTS) {
            String folder = path.substring(0, path.indexOf('/'));
            Matcher texture = TEXTURE.matcher(document(path));
            while (texture.find()) {
                String spelled = texture.group(1);
                String resolved;
                if (spelled.startsWith("UI/Custom/")) {
                    resolved = spelled.substring("UI/Custom/".length());
                } else if (spelled.startsWith("../")) {
                    resolved = spelled.substring(3);
                } else {
                    resolved = folder + "/" + spelled;
                }
                if (!VANILLA_TEXTURES.contains(resolved) && !ships(resolved)) {
                    bad.add(path + ": " + spelled);
                }
            }
        }
        assertTrue(bad.isEmpty(), "a texture path that resolves to nothing draws nothing, silently: " + bad);
    }

    @Test
    void theGlyphsAndHeroTexturesAreTheSizesTheDocumentsDrawThemAt() throws IOException {
        for (String glyph : GLYPHS) {
            assertSize("Common/Glyphs/" + glyph + ".png", 20, 20);
            assertSize("Common/Glyphs/" + glyph + "@2x.png", 40, 40);
        }
        assertSize("Common/ZigHeroFade.png", 962, 140);
        assertSize("Common/ZigHeroFade@2x.png", 1924, 280);
        assertSize("Common/ZigHeroPlate.png", 962, 240);
        assertSize("Common/ZigHeroPlate@2x.png", 1924, 480);
        assertSize("Common/Kit/HeroGradient.png", 4, 240);
        assertSize("Common/Kit/HeroGradient@2x.png", 8, 480);
        assertSize("Common/Kit/HeroGlow.png", 256, 256);
        assertSize("Common/Kit/HeroGlow@2x.png", 512, 512);
        BufferedImage gradient = image("Common/Kit/HeroGradient.png");
        assertEquals(0xffffffff, gradient.getRGB(0, 0), "the gradient is opaque white at the top");
        assertEquals(0, gradient.getRGB(0, gradient.getHeight() - 1) >>> 24, "and transparent at the bottom");
        BufferedImage glow = image("Common/Kit/HeroGlow.png");
        assertEquals(0xffffff, glow.getRGB(128, 128) & 0xffffff, "the glow is white");
        assertTrue(glow.getRGB(128, 128) >>> 24 > 240, "the glow is opaque at its centre");
        assertEquals(0, glow.getRGB(0, 0) >>> 24, "and transparent at its edge");
        BufferedImage blank = image("Common/Glyphs/Blank.png");
        for (int x = 0; x < blank.getWidth(); x++) {
            for (int y = 0; y < blank.getHeight(); y++) {
                assertEquals(0, blank.getRGB(x, y) >>> 24, "Blank is transparent");
            }
        }
        BufferedImage dot = image("Common/Glyphs/Dot@2x.png");
        int centre = dot.getRGB(20, 20);
        assertEquals(0xffffff, centre & 0xffffff, "a glyph is white, so a Color tint takes it to any tone");
        assertTrue(centre >>> 24 > 200, "the dot is solid at its centre");
    }

    // ---- helpers ----

    /** The row ids every ledger row (standard and compact) shares, inside its one #Select button. */
    private static void assertLedgerRow(@Nonnull String ui, int picture) throws IOException {
        assertEquals("Button", type(ui, "#Select"));
        String select = block(ui, "#Select");
        assertEquals("$ZS.@ZigRowStyle", property(select, "Style"));
        assertEquals("Group", type(select, "#Accent"));
        assertEquals(4, size(leaf(property(block(select, "#Accent"), "Anchor"), "Width")), "a 4px tone bar");
        assertPicture(select, "#Pic", picture);
        assertEquals("Label", type(select, "#Title"));
        assertGlyphHolder(select, "#Mark", 16, Map.of("#Pinned", "PinFilled", "#Tracked", "Pin"));
        assertHidden(select, "#Mark");
        assertBar(select, 4);
        assertHidden(select, "#BarTrack");
        assertEquals("Group", type(select, "#Trail"));
        assertEquals(92, size(leaf(property(block(select, "#Trail"), "Anchor"), "Width")), "a 92px trail");
        String trail = block(select, "#Trail");
        assertEquals("$ZX.@ZigCountStyle", property(block(trail, "#Value"), "Style"));
        assertEquals("$ZS.@ZigStateStyle", property(block(trail, "#State"), "Style"));
        assertEquals(1, count(ui, "\\bButton\\s+#"), "no other button in a row");
        assertFalse(ui.contains("TooltipText"), "no tooltip in a row");
    }

    /** {@code id} is a {@code @ZigPicture} instance at {@code rung} pixels. */
    private static void assertPicture(@Nonnull String ui, @Nonnull String id, int rung) throws IOException {
        assertTrue(type(ui, id).endsWith("@ZigPicture"), id + " is the kit's one picture slot");
        assertEquals(rung, size(parameter(block(ui, id), "Size")), id + " is the " + rung + "px rung");
    }

    /** A {@code @ZigProgressBar #BarTrack} of {@code height} (its {@code #Bar} comes from the template). */
    private static void assertBar(@Nonnull String ui, int height) throws IOException {
        assertTrue(type(ui, "#BarTrack").endsWith("@ZigProgressBar"), "#BarTrack is the kit's progress bar");
        assertEquals(height, size(parameter(block(ui, "#BarTrack"), "Height")), "a " + height + "px bar");
    }

    /** A hidden holder of one hidden glyph child per state, each a tintable patch of its glyph. */
    private static void assertGlyphHolder(@Nonnull String ui, @Nonnull String holder, int px,
            @Nonnull Map<String, String> children) throws IOException {
        String block = block(ui, holder);
        assertEquals("Group", type(ui, holder));
        String anchor = property(block, "Anchor");
        assertEquals(px, size(leaf(anchor, "Width")), holder + " is " + px + "px");
        assertEquals(px, size(leaf(anchor, "Height")), holder + " is " + px + "px");
        for (Map.Entry<String, String> child : children.entrySet()) {
            String glyph = block(block, child.getKey());
            assertEquals("false", property(glyph, "Visible"), child.getKey() + " ships hidden");
            assertTintable(child.getKey(), property(glyph, "Background"), child.getValue());
        }
    }

    /** A hidden single glyph. */
    private static void assertGlyph(@Nonnull String ui, @Nonnull String id, int px, @Nonnull String glyph)
            throws IOException {
        String block = block(ui, id);
        assertEquals("Group", type(ui, id));
        assertEquals(px, size(leaf(property(block, "Anchor"), "Width")), id + " is " + px + "px");
        assertEquals(px, size(leaf(property(block, "Anchor"), "Height")), id + " is " + px + "px");
        assertEquals("false", property(block, "Visible"), id + " ships hidden");
        assertTintable(id, property(block, "Background"), glyph);
    }

    /**
     * A patch of the glyph's texture with a {@code Color} leaf Java retints, spelled as zc's dialogue row spells its
     * tinted glyphs ({@code Background: (TexturePath: ..., Color: ...)}).
     */
    private static void assertTintable(@Nonnull String id, String background, @Nonnull String glyph) {
        assertNotNull(background, id + " has a background");
        assertTrue(background.startsWith("(TexturePath: ") && background.contains("/" + glyph + ".png\"")
                && background.contains("Color: $ZK.@"), id + " is a tintable " + glyph + " patch: " + background);
    }

    private static void assertHidden(@Nonnull String ui, @Nonnull String id) {
        assertEquals("false", property(block(ui, id), "Visible"), id + " ships hidden");
    }

    private static void assertStyled(@Nonnull String button) {
        String style = property(button, "Style");
        assertTrue(style != null && style.startsWith("$ZS.@"), "a kit button takes a named style: " + style);
    }

    private static void assertOneLine(String style, @Nonnull String base) {
        assertTrue(style != null && style.contains(base) && style.contains("WrapMaxLines: 1"),
                "one line, ended with an ellipsis: " + style);
    }

    private static void assertNull(Object value, @Nonnull String message) {
        assertTrue(value == null, message + " (found " + value + ")");
    }

    private static void assertSize(@Nonnull String path, int width, int height) throws IOException {
        BufferedImage image = image(path);
        assertEquals(width, image.getWidth(), path + " width");
        assertEquals(height, image.getHeight(), path + " height");
    }

    @Nonnull
    private static BufferedImage image(@Nonnull String path) throws IOException {
        try (InputStream in = ZigKitDocumentsTest.class.getResourceAsStream("/Common/UI/Custom/" + path)) {
            assertNotNull(in, "the classpath ships " + path);
            BufferedImage image = ImageIO.read(in);
            assertNotNull(image, path + " is a PNG");
            return image;
        }
    }

    private static boolean ships(@Nonnull String underCustom) {
        return ZigKitDocumentsTest.class.getResource("/Common/UI/Custom/" + underCustom) != null;
    }

    /** The index of every brace that opens at the document's top level. */
    @Nonnull
    private static List<Integer> topLevelBraces(@Nonnull String ui) {
        List<Integer> out = new ArrayList<>();
        int depth = 0;
        for (int i = 0; i < ui.length(); i++) {
            char c = ui.charAt(i);
            if (c == '{') {
                if (depth == 0) {
                    out.add(i);
                }
                depth++;
            } else if (c == '}') {
                depth--;
            }
        }
        return out;
    }

    private static int count(@Nonnull String ui, @Nonnull String regex) {
        Matcher m = Pattern.compile(regex).matcher(ui);
        int n = 0;
        while (m.find()) {
            n++;
        }
        return n;
    }

    @Nonnull
    private static List<String> documents() throws IOException {
        List<String> out = new ArrayList<>();
        for (String path : DOCUMENTS) {
            out.add(document(path));
        }
        return out;
    }
}
