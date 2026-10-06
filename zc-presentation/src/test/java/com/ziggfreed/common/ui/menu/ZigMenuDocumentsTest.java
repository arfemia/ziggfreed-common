package com.ziggfreed.common.ui.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.Nonnull;

import org.junit.jupiter.api.Test;

/**
 * The frame and the rail row against what the Java addresses and what the readability standards ask:
 * one size, the frame contract a theme retints ({@code #Content}, both ornaments, the close button and
 * a {@code Background} on {@code #MenuRail}), every element {@link ZigMenu} sets, no text under 12px,
 * a four-step type scale, and no colour that is not one of the default palette's ({@link MenuPalette}). A command against an
 * element the document lacks disconnects the player, which no server-side check sees.
 */
class ZigMenuDocumentsTest {

    private static final Pattern HEX = Pattern.compile("(?<![0-9A-Za-z])#[0-9a-fA-F]{6}(?![0-9A-Za-z])");
    private static final Pattern FONT = Pattern.compile("FontSize:\\s*(\\d+)");
    private static final List<String> STYLES = List.of("@ZigCaptionLabelStyle", "@ZigBodyLabelStyle",
            "@ZigHeadingLabelStyle", "@ZigTitleLabelStyle");

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
        assertTrue(own(rail).contains("Background:"), "a theme retints the rail's Background; without one the client crashes");
    }

    @Test
    void theRailRowDeclaresEveryElementThePainterAddresses() throws IOException {
        String row = document(ZigMenu.TAB_TEMPLATE);
        for (String id : List.of("#ZigMenuTab", ZigMenu.ROW_LINE, ZigMenu.MARKER, ZigMenu.ICON_SLOT, "#IcoItem",
                "#IcoTex", ZigMenu.BUTTON, "#Label", ZigMenu.HEADER, ZigMenu.SPACER)) {
            assertTrue(Pattern.compile("\\w+\\s+" + Pattern.quote(id) + "\\s*\\{").matcher(row).find(),
                    ZigMenu.TAB_TEMPLATE + " declares " + id);
        }
        for (String hidden : List.of(ZigMenu.MARKER, ZigMenu.ICON_SLOT, ZigMenu.HEADER, ZigMenu.SPACER)) {
            assertTrue(own(block(row, hidden)).contains("Visible: false"), hidden + " ships hidden");
        }
        assertTrue(row.contains("Button " + ZigMenu.BUTTON + " {"), "the tab is a Button labelled on .TextSpans, never a TextButton");
    }

    @Test
    void theTypeScaleIsFourStepsAndNoTextIsUnderTheFloor() throws IOException {
        String frames = document("Common/ZigFrames.ui");
        int[] sizes = {MenuFrame.CAPTION_SIZE, MenuFrame.BODY_SIZE, MenuFrame.HEADING_SIZE, MenuFrame.TITLE_SIZE};
        for (int i = 0; i < STYLES.size(); i++) {
            Matcher m = Pattern.compile(Pattern.quote(STYLES.get(i)) + "\\s*=\\s*LabelStyle\\(FontSize:\\s*(\\d+)").matcher(frames);
            assertTrue(m.find(), "ZigFrames.ui defines " + STYLES.get(i));
            assertEquals(sizes[i], Integer.parseInt(m.group(1)), STYLES.get(i));
        }
        for (String region : regions()) {
            Matcher font = FONT.matcher(region);
            while (font.find()) {
                assertTrue(Integer.parseInt(font.group(1)) >= MenuFrame.FONT_FLOOR,
                        "text in the frame or rail at " + font.group(1) + "px, under the " + MenuFrame.FONT_FLOOR + "px floor");
            }
        }
    }

    @Test
    void everyColourInTheFrameAndRailIsAToken() throws IOException {
        for (String region : regions()) {
            Matcher hex = HEX.matcher(region);
            while (hex.find()) {
                assertTrue(MenuPalette.defaultColours().contains(hex.group().toLowerCase(Locale.ROOT)),
                        hex.group() + " is not a default palette colour, so MenuPaletteTest cannot measure it and the palette cannot repaint it");
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
            Matcher line = Pattern.compile(Pattern.quote(style) + "\\s*=\\s*LabelStyle\\([^;]*;").matcher(frames);
            assertTrue(line.find(), style);
            out.add(line.group());
        }
        out.add(document(ZigMenu.TAB_TEMPLATE));
        return out;
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
