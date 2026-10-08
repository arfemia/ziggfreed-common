package com.ziggfreed.common.ui.menu;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.Nonnull;

import org.junit.jupiter.api.Test;

/**
 * The frame and the rail row against what the Java addresses, what vanilla's own rail is and what the
 * readability standards ask: one size, the frame contract a theme retints, the pane and the separator, every
 * element {@link ZigMenu} sets, vanilla's row (32 high, 4 below, a body-size uppercase label shrinking to the
 * floor, the selected label under the gold mask, a picture before the label), every size a step of the family's
 * type scale ({@code Common/ZigType.ui}, never a number, so none is under its floor), four label styles on it,
 * and no colour that is not one of the default palette's ({@link MenuPalette}, an alpha one spelled
 * {@code #rrggbb(a)}). A command against an element the document lacks disconnects the player, which no
 * server-side check sees.
 */
class ZigMenuDocumentsTest {

    /** A colour as a document spells it: {@code #rrggbb}, optionally followed by vanilla's alpha {@code (a)}. */
    private static final Pattern COLOUR = Pattern.compile("(?<![0-9A-Za-z])#[0-9a-fA-F]{6}(?:\\([0-9.]+\\))?(?![0-9A-Za-z])");
    /** A size written as a number ({@code FontSize} or {@code MinShrinkTextToFitFontSize}), not a type-scale step. */
    private static final Pattern NUMBER_SIZE = Pattern.compile("FontSize:\\s*\\d");
    private static final List<String> STYLES = List.of("@ZigCaptionLabelStyle", "@ZigBodyLabelStyle",
            "@ZigHeadingLabelStyle", "@ZigTitleLabelStyle");
    private static final List<String> STEPS = List.of("@ZigFontCaption", "@ZigFontBody", "@ZigFontHeading",
            "@ZigFontTitle");

    @Test
    void theMenuFrameIsTheOneSizeAndCarriesTheFrameContract() throws IOException {
        String frame = template(document("Common/ZigFrames.ui"), "@ZigMenuFrame");

        assertTrue(frame.contains("Anchor: (Width: " + MenuFrame.FRAME_WIDTH + ", Height: "
                + MenuFrame.FRAME_HEIGHT + ")"), "the frame is MenuFrame's one size");
        for (String declared : List.of("Group #Content", "Group #DecorTop", "Group #DecorBottom",
                "Button #CloseButton", "Group #MenuRail", "Group #MenuList", "Group #BrandingContainerLeft",
                "Label #BrandingServerName", "Label #BrandingDescription")) {
            assertTrue(frame.contains(declared + " {"), "the frame declares " + declared);
        }
        assertTrue(own(block(frame, "#Content")).contains("Padding: (Full: " + MenuFrame.FRAME_PADDING + ")"));
        String rail = block(frame, MenuFrame.RAIL);
        assertTrue(own(rail).contains("Width: " + MenuFrame.RAIL_WIDTH), "the rail is MenuFrame's rail width");
        assertTrue(own(rail).contains("Padding: (Full: " + MenuFrame.RAIL_PADDING + ")"), "the pane's padding");
        assertTrue(own(rail).contains("Background: (Color: #000000(0.15))"),
                "the rail sits on vanilla's faint pane, a Background a theme repaints (without one the client crashes)");
        assertFalse(rail.contains("ContainerPanelPatch"), "no panel patch under the rail");
    }

    /**
     * The rail's branding name and description wrap instead of clipping (M364): neither has a fixed height, so a
     * wrapped label in the rail's Top stack sizes itself to its lines (vanilla {@code PrefabEditorExitConfirm.ui}),
     * and each keeps a bottom margin before what follows. Both still ship hidden, since a painter shows them.
     */
    @Test
    void theRailsBrandingLabelsWrapInsteadOfClipping() throws IOException {
        String rail = block(template(document("Common/ZigFrames.ui"), "@ZigMenuFrame"), MenuFrame.RAIL);
        for (String id : List.of("#BrandingServerName", "#BrandingDescription")) {
            String label = own(block(rail, id));
            Matcher anchor = Pattern.compile("Anchor:\\s*\\(([^)]*)\\)").matcher(label);
            assertTrue(anchor.find(), id + " keeps an Anchor for its margin");
            assertFalse(anchor.group(1).contains("Height"),
                    id + " has no fixed height, so a wrapped line is not clipped: " + anchor.group());
            assertTrue(anchor.group(1).contains("Bottom:"), id + " keeps its bottom margin: " + anchor.group());
            Matcher style = Pattern.compile("Style:\\s*([^;]+);").matcher(label);
            assertTrue(style.find(), id + " has a style");
            assertTrue(style.group(1).contains("Wrap: true"), id + "'s style wraps: " + style.group(1));
            assertFalse(style.group(1).contains("WrapMaxLines: 1"), id + " is not held to one line: " + style.group(1));
            assertTrue(label.contains("Visible: false"), id + " ships hidden until a painter shows it");
        }
        assertTrue(own(block(rail, "#BrandingServerName")).contains("@ZigBodyLabelStyle"),
                "the name keeps the body step");
        assertTrue(own(block(rail, "#BrandingDescription")).contains("@ZigCaptionLabelStyle"),
                "the description keeps the caption step");
    }

    @Test
    void theSeparatorStandsBetweenTheRailAndThePage() throws IOException {
        String frame = template(document("Common/ZigFrames.ui"), "@ZigMenuFrame");
        String content = block(frame, "#Content");

        assertTrue(content.contains("$C.@VerticalSeparator #MenuSeparator {"), "vanilla's own vertical separator");
        String separator = own(block(content, "#MenuSeparator"));
        assertTrue(separator.contains("Width: " + MenuFrame.SEPARATOR_WIDTH), "MenuFrame's separator width");
        assertTrue(separator.contains("Left: " + MenuFrame.SEPARATOR_MARGIN + ", Right: " + MenuFrame.SEPARATOR_MARGIN),
                "MenuFrame's separator spacing, so BODY_WIDTH is what the page gets");
        assertTrue(content.indexOf("#MenuSeparator {") > content.indexOf("Group #MenuRail {"),
                "after the rail, so a page's columns follow it");
        assertFalse(block(frame, MenuFrame.RAIL).contains("#MenuSeparator"), "beside the rail, not inside it");
    }

    @Test
    void theRailRowDeclaresEveryElementThePainterAddresses() throws IOException {
        String row = document(ZigMenu.TAB_TEMPLATE);
        for (String id : List.of("#ZigMenuTab", ZigMenu.BUTTON, ZigMenu.ICON_SLOT, "#Label", ZigMenu.SELECTED,
                ZigMenu.MARKER, ZigMenu.SELECTED_ICON_SLOT, ZigMenu.SELECTED_LABEL, ZigMenu.HEADER, ZigMenu.RULE,
                "#IcoTex")) {
            assertTrue(Pattern.compile("\\w+\\s+" + Pattern.quote(id) + "\\s*\\{").matcher(row).find(),
                    ZigMenu.TAB_TEMPLATE + " declares " + id);
        }
        for (String hidden : List.of(ZigMenu.SELECTED, ZigMenu.ICON_SLOT, ZigMenu.SELECTED_ICON_SLOT, ZigMenu.HEADER,
                ZigMenu.RULE)) {
            assertTrue(own(block(row, hidden)).contains("Visible: false"), hidden + " ships hidden");
        }
        assertTrue(row.contains("Button " + ZigMenu.BUTTON + " {"), "the tab is a Button labelled on .TextSpans");
        assertFalse(row.contains("TextButton"), "never a TextButton");
        for (String slot : List.of(ZigMenu.ICON_SLOT, ZigMenu.SELECTED_ICON_SLOT)) {
            String widgets = block(row, slot);
            assertTrue(widgets.contains("AssetImage #IcoTex {") && !widgets.contains("#IcoItem"),
                    slot + " holds the one plain picture IconRenderer.applyPlainIcon paints");
        }
        assertFalse(row.contains("ItemGrid"),
                "a tab's picture only displays: an item grid shows the item's tooltip and rarity square on the tab");
        assertFalse(Pattern.compile("\\bItemIcon\\b").matcher(row).find(), "an ItemIcon draws blank in game");
    }

    @Test
    void theRowIsVanillasOwnRow() throws IOException {
        String row = document(ZigMenu.TAB_TEMPLATE);
        assertTrue(own(block(row, "#ZigMenuTab")).contains("Height: " + MenuFrame.ROW_HEIGHT + ", Bottom: "
                + MenuFrame.ROW_GAP), "32 high, 4 below");

        String button = block(row, ZigMenu.BUTTON);
        assertTrue(own(button).contains("Padding: (Horizontal: " + MenuFrame.ROW_PADDING + ")"), "10 in from each side");
        assertTrue(own(button).contains("Default: (Background: (Color: #000000(0)))"), "no resting fill");
        assertTrue(own(button).contains("Hovered: (Background: (Color: #000000(0.2)))"), "vanilla's hover fill");
        assertTrue(own(button).contains("Sounds: $C.@ButtonSounds"), "a custom ButtonStyle clicks only with Sounds");

        String tabLabel = styleLine(row, "@TabLabelStyle");
        String selectedLabel = styleLine(row, "@SelectedLabelStyle");
        for (String label : List.of(tabLabel, selectedLabel)) {
            assertTrue(label.contains("FontSize: $ZT.@ZigFontBody,"), "the body step, MenuFrame.LABEL_SIZE: " + label);
            assertTrue(label.contains("RenderUppercase: true"), label);
            assertTrue(label.contains("ShrinkTextToFit: true"), label);
            assertTrue(label.contains("MinShrinkTextToFitFontSize: $ZT.@ZigFontCaption"),
                    "shrinks no further than the floor, MenuFrame.LABEL_MIN_SIZE: " + label);
        }
        assertTrue(selectedLabel.contains("RenderBold: true"), "the selected label is bold");
        assertTrue(own(block(button, "#Label")).contains("Style: @TabLabelStyle"));
        String masked = own(block(row, ZigMenu.SELECTED_LABEL));
        assertTrue(masked.contains("Style: @SelectedLabelStyle"));
        assertTrue(masked.contains("MaskTexturePath: $C.@TextHighlightGradientMask"),
                "vanilla's gold gradient mask, authored in the row since no Java push sets it");

        String section = styleLine(row, "@SectionLabelStyle");
        assertTrue(section.contains("FontSize: $ZT.@ZigFontSection,")
                && section.contains("RenderBold: true") && section.contains("RenderUppercase: true"), section);
        assertTrue(own(block(row, ZigMenu.HEADER)).contains("Style: @SectionLabelStyle"));

        assertTrue(own(block(row, ZigMenu.MARKER)).contains("Width: " + MenuFrame.MARKER_WIDTH + ","), "a slim bar");
        assertTrue(own(block(row, ZigMenu.RULE)).contains("Height: 1)"), "vanilla's 1px rule");
    }

    @Test
    void aPictureComesBeforeItsLabel() throws IOException {
        String row = document(ZigMenu.TAB_TEMPLATE);
        String button = block(row, ZigMenu.BUTTON);
        assertTrue(button.indexOf(ZigMenu.ICON_SLOT + " {") < button.indexOf("Label #Label {"),
                "the picture sits before the label");
        String selected = block(row, ZigMenu.SELECTED);
        int bar = selected.indexOf(ZigMenu.MARKER + " {");
        int picture = selected.indexOf(ZigMenu.SELECTED_ICON_SLOT + " {");
        int label = selected.indexOf(ZigMenu.SELECTED_LABEL + " {");
        assertTrue(bar >= 0 && bar < picture && picture < label, "the bar, the picture, the label");
        for (String slot : List.of(ZigMenu.ICON_SLOT, ZigMenu.SELECTED_ICON_SLOT)) {
            String anchor = own(block(row, slot));
            assertTrue(anchor.contains("Width: " + MenuFrame.ICON_SIZE + ", Height: " + MenuFrame.ICON_SIZE),
                    slot + " is MenuFrame's picture size");
            assertTrue(anchor.contains("Top: " + MenuFrame.ICON_INSET), slot + " sits centred in the row");
            assertTrue(anchor.contains("Right: " + MenuFrame.ICON_GAP), slot + " keeps its gap before the label");
            String size = "Anchor: (Width: " + MenuFrame.ICON_SIZE + ", Height: " + MenuFrame.ICON_SIZE + ");";
            String widgets = block(row, slot);
            assertTrue(own(block(widgets, "#IcoTex")).contains(size), slot + "'s picture fills the slot");
        }
    }

    @Test
    void everySizeInTheFrameAndRailIsATypeScaleStep() throws IOException {
        String frames = document("Common/ZigFrames.ui");
        assertTrue(frames.contains("$ZT = \"ZigType.ui\";"), "ZigFrames.ui imports the type scale beside it");
        assertTrue(document(ZigMenu.TAB_TEMPLATE).contains("$ZT = \"../Common/ZigType.ui\";"),
                ZigMenu.TAB_TEMPLATE + " imports the type scale");
        for (int i = 0; i < STYLES.size(); i++) {
            assertTrue(styleLine(frames, STYLES.get(i)).contains("FontSize: $ZT." + STEPS.get(i) + ","),
                    STYLES.get(i) + " is sized by " + STEPS.get(i));
        }
        for (String region : regions()) {
            assertFalse(NUMBER_SIZE.matcher(region).find(),
                    "a size in the frame or rail is a Common/ZigType.ui step, never a number, so none is under the "
                            + "floor and one edit there resizes them all: " + region);
        }
    }

    @Test
    void everyColourInTheFrameAndRailIsAToken() throws IOException {
        for (String region : regions()) {
            Matcher colour = COLOUR.matcher(region);
            while (colour.find()) {
                assertTrue(MenuPalette.defaultColours().contains(MenuPalette.fromMarkup(colour.group())),
                        colour.group() + " is not a default palette colour, so MenuPaletteTest cannot measure it and the palette cannot repaint it");
            }
        }
    }

    /** The frame block, the type-scale lines and the whole rail row. */
    @Nonnull
    private static List<String> regions() throws IOException {
        String frames = document("Common/ZigFrames.ui");
        List<String> out = new ArrayList<>();
        out.add(template(frames, "@ZigMenuFrame"));
        for (String style : STYLES) {
            out.add(styleLine(frames, style));
        }
        out.add(document(ZigMenu.TAB_TEMPLATE));
        return out;
    }

    /** The one line {@code @Name = LabelStyle(...);} defines. */
    @Nonnull
    private static String styleLine(@Nonnull String ui, @Nonnull String name) {
        Matcher line = Pattern.compile(Pattern.quote(name) + "\\s*=\\s*LabelStyle\\([^;]*;").matcher(ui);
        assertTrue(line.find(), "the document defines " + name);
        return line.group();
    }

    /** A shipped document under {@code Common/UI/Custom/}, with every {@code //} comment removed. */
    @Nonnull
    static String document(@Nonnull String path) throws IOException {
        try (InputStream in = ZigMenuDocumentsTest.class.getResourceAsStream("/Common/UI/Custom/" + path)) {
            assertNotNull(in, "the classpath ships " + path);
            StringBuilder out = new StringBuilder();
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                int at = line.indexOf("//");
                out.append(at < 0 ? line : line.substring(0, at)).append('\n');
            }
            return out.toString();
        }
    }

    /** The body of {@code @Name = Group { ... };}. */
    @Nonnull
    static String template(@Nonnull String ui, @Nonnull String name) {
        Matcher m = Pattern.compile(Pattern.quote(name) + "\\s*=\\s*\\w+\\s*\\{").matcher(ui);
        assertTrue(m.find(), "the document defines " + name);
        return braces(ui, m.end() - 1);
    }

    /** The block element {@code id} declares. */
    @Nonnull
    static String block(@Nonnull String ui, @Nonnull String id) {
        Matcher m = Pattern.compile("\\w+\\s+" + Pattern.quote(id) + "\\s*\\{").matcher(ui);
        assertTrue(m.find(), "declares " + id);
        return braces(ui, m.end() - 1);
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

    @Nonnull
    private static String braces(@Nonnull String ui, int open) {
        int depth = 0;
        for (int i = open; i < ui.length(); i++) {
            char c = ui.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return ui.substring(open, i + 1);
            }
        }
        throw new AssertionError("unbalanced braces from " + open);
    }
}
