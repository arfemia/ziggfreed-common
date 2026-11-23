package com.ziggfreed.common.ui.menu;

import static com.ziggfreed.common.ui.menu.ZigMenuDocumentsTest.block;
import static com.ziggfreed.common.ui.menu.ZigMenuDocumentsTest.document;
import static com.ziggfreed.common.ui.menu.ZigMenuDocumentsTest.own;
import static com.ziggfreed.common.ui.menu.ZigMenuDocumentsTest.template;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.Nonnull;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.protocol.packets.interface_.CustomUICommand;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.ziggfreed.common.ui.ZigFrame;
import com.ziggfreed.common.ui.theme.Palette;

/**
 * The frames' contract ({@code Common/ZigFrames.ui} against {@link ZigFrame}): the band is one template,
 * {@code @ZigBand}, that both band frames draw and the panel frame never does (a HUD on the panel must never blur or
 * dim the world), its pieces reach past the frame's box by {@link ZigFrame}'s numbers so a page's own Anchor keeps its
 * meaning, and each piece a theme paints carries the band's Background; the decorated frame keeps its two params and
 * declares every id {@link ZigFrame} names, the hero hidden until a painter fills it and the boxed frame's ornaments
 * hidden under the band; and the menu's own retint pushes only onto ids its frame declares with a Background, since a
 * command against a missing id, or a leaf push on an unset Background, disconnects the player.
 */
class ZigFrameDocumentTest {

    private static final Pattern PUSH = Pattern.compile("^(#[A-Za-z][A-Za-z0-9]*)(\\..*)?$");

    @Test
    void theBandIsOneTemplateThatOnlyTheBandFramesDraw() throws IOException {
        String frames = document("Common/ZigFrames.ui");
        String band = template(frames, "@ZigBand");

        String blur = own(block(band, ZigFrame.BLUR));
        assertTrue(band.contains("SceneBlur " + ZigFrame.BLUR + " {"), "the blur is the game's own SceneBlur");
        String scrim = own(block(band, ZigFrame.SCRIM));
        for (String cover : List.of(blur, scrim)) {
            for (String edge : List.of("Left", "Right", "Top", "Bottom")) {
                assertTrue(cover.contains(edge + ": -" + ZigFrame.SCREEN_REACH),
                        "the blur and the scrim reach past the box to every edge of the screen: " + cover);
            }
        }
        assertTrue(scrim.contains("Background: (Color: $ZK.@ZigBandScrim)"), "the scrim is the kit's token");

        for (String piece : ZigFrame.BAND_PIECES) {
            assertTrue(band.contains("Group " + piece + " {"), "the band declares " + piece);
            assertTrue(own(block(band, piece)).contains("Background: (TexturePath: \"" + ZigFrame.BAND_TEXTURE
                    + "\", VerticalBorder: " + ZigFrame.BAND_HEAD + ", HorizontalBorder: 4)"),
                    piece + " carries the band's own Background, its head and foot kept whole, which a theme paints");
        }
        String middle = own(block(band, ZigFrame.BAND));
        assertTrue(middle.contains("Anchor: (Width: " + ZigFrame.BAND_WIDTH + ", Top: -" + ZigFrame.BAND_HEAD
                + ", Bottom: -" + ZigFrame.BAND_HEAD + ")"),
                "the middle piece is centred on the box and reaches its head and foot past it: " + middle);
        assertTrue(own(block(band, ZigFrame.BAND_LEFT)).contains("Width: " + ZigFrame.BAND_REACH + ", Left: -"
                + ZigFrame.BAND_REACH), "the left piece carries the band on past the middle one's left edge");
        assertTrue(own(block(band, ZigFrame.BAND_RIGHT)).contains("Width: " + ZigFrame.BAND_REACH + ", Right: -"
                + ZigFrame.BAND_REACH), "the right piece carries the band on past the middle one's right edge");

        for (String frame : List.of("@ZigDecoratedFrame", "@ZigMenuFrame")) {
            assertTrue(template(frames, frame).contains("@ZigBand {}"), frame + " draws the band");
        }
        String panel = template(frames, "@ZigPanelFrame");
        assertFalse(panel.contains("@ZigBand") || panel.contains("SceneBlur") || panel.contains(ZigFrame.BAND),
                "the panel frame draws no blur, scrim or band: a HUD on it never dims the world during play");
    }

    @Test
    void theDecoratedFrameDeclaresWhatZigFrameNames() throws IOException {
        String frame = template(document("Common/ZigFrames.ui"), "@ZigDecoratedFrame");
        String params = own(frame);
        assertTrue(params.contains("@CloseButton = false;") && params.contains("@Ornaments = true;"),
                "both of the frame's params are still accepted: a page that sets either still parses");

        String content = block(frame, ZigFrame.CONTENT);
        assertTrue(own(content).contains("Background:"),
                "the column carries a Background, so a push onto it never lands on an unset one");
        assertTrue(own(content).contains("LayoutMode: Top"), "the column stacks a page's children as it always has");

        String hero = block(content, ZigFrame.HERO);
        assertTrue(own(hero).contains("Visible: false"), "the hero is hidden until a painter fills it");
        for (String id : List.of(ZigFrame.HERO_EMBLEM, ZigFrame.HERO_EMBLEM_ART, ZigFrame.HERO_HEADLINE,
                ZigFrame.HERO_TAGLINE, ZigFrame.HERO_RULE, ZigFrame.HERO_SUB)) {
            assertTrue(Pattern.compile("\\w+\\s+" + Pattern.quote(id) + "\\s*\\{").matcher(hero).find(),
                    "the hero declares " + id);
        }
        for (String hidden : List.of(ZigFrame.HERO_EMBLEM, ZigFrame.HERO_TAGLINE, ZigFrame.HERO_SUB)) {
            assertTrue(own(block(hero, hidden)).contains("Visible: false"), hidden + " ships hidden");
        }
        assertTrue(hero.contains("AssetImage " + ZigFrame.HERO_EMBLEM_ART + " {"), "the emblem is a plain picture");
        assertTrue(own(block(hero, ZigFrame.HERO_HEADLINE)).contains("MaskTexturePath:"),
                "the title carries the game's gold text gradient, which no style can");
        String titleRow = block(hero, "#HeroTitleRow");
        String headline = own(block(titleRow, ZigFrame.HERO_HEADLINE));
        assertTrue(own(titleRow).contains("LayoutMode: Left") && !titleRow.contains("Group ")
                && headline.contains("FlexWeight: 1") && headline.contains("$ZX.@ZigDisplayTitleStyle"),
                "the title is its row's own child and takes the row's whole width, so its shrink-to-fit has a bound: "
                        + "a long name shrinks inside the column, never past it onto the band");
        assertTrue(content.indexOf("Group " + ZigFrame.HERO + " {") < content.indexOf("}"),
                "the hero is the column's first child, so a page's own children follow it");

        for (String ornament : List.of(ZigFrame.DECOR_TOP, ZigFrame.DECOR_BOTTOM)) {
            assertTrue(own(block(frame, ornament)).contains("Visible: false"),
                    ornament + " stays declared, hidden under the band's own head and foot");
        }
        assertTrue(own(block(frame, ZigFrame.CLOSE)).contains("Visible: @CloseButton;"), "the close button's param");
    }

    @Test
    void thePanelFrameKeepsTheBoxedBodyAHudTints() throws IOException {
        String frame = template(document("Common/ZigFrames.ui"), "@ZigPanelFrame");
        String params = own(frame);
        assertTrue(params.contains("@CloseButton = false;") && params.contains("@Ornaments = true;"));
        assertTrue(own(block(frame, ZigFrame.CONTENT)).contains("Background: (TexturePath:"),
                "the panel's body is a patch a HUD card's colour multiplies (RPG Stations' summary)");
        for (String id : List.of(ZigFrame.DECOR_TOP, ZigFrame.DECOR_BOTTOM, ZigFrame.CLOSE)) {
            assertTrue(Pattern.compile("\\w+\\s+" + Pattern.quote(id) + "\\s*\\{").matcher(frame).find(),
                    "the panel frame declares " + id);
        }
    }

    /**
     * Every push the library's own menu paint makes, for a tinted palette, a texture set with no band art, a theme's
     * own band art, and both, lands on an id the menu frame (with its band) declares, a {@code .Background} leaf only
     * where that element carries one. Tagged {@code engine-items}: a {@link UICommandBuilder}'s static init reaches the
     * engine's item codec.
     */
    @Test
    @Tag("engine-items")
    void theMenuRetintPushesOnlyOntoDeclaredBackgrounds() throws IOException {
        String frames = document("Common/ZigFrames.ui");
        String frame = template(frames, "@ZigMenuFrame") + "\n" + template(frames, "@ZigBand");

        Palette tinted = MenuPalette.defaults();
        tinted.frame = "#b5532a";
        tinted.primary = "#2a1410";
        Palette textured = MenuPalette.defaults();
        textured.textureDir = "Common/Molten/";
        Palette banded = MenuPalette.defaults();
        banded.bandTexture = "Common/Molten/BackgroundModal.png";
        Palette both = textured.copy();
        both.bandTexture = banded.bandTexture;

        List<String> failures = new ArrayList<>();
        for (Palette palette : List.of(tinted, textured, banded, both)) {
            UICommandBuilder cmd = new UICommandBuilder();
            MenuFrameRetint.apply(cmd, palette, MenuFrame.RAIL);
            List<String> pushed = new ArrayList<>();
            for (CustomUICommand command : cmd.getCommands()) {
                pushed.add(command.selector);
                String why = undeclared(frame, command.selector);
                if (why != null) {
                    failures.add(command.selector + "  <-  " + why);
                }
            }
            if (palette == tinted || palette.hasBandTexture()) {
                for (String piece : ZigFrame.BAND_PIECES) {
                    assertTrue(pushed.stream().anyMatch(s -> s.startsWith(piece + ".")),
                            "the band's piece " + piece + " is painted: " + pushed);
                }
            }
        }
        assertTrue(failures.isEmpty(), "the menu paint pushes onto what its frame lacks:\n" + String.join("\n", failures));
    }

    /** Why {@code selector} names nothing {@code frame} declares with what its property path needs, or null. */
    private static String undeclared(@Nonnull String frame, @Nonnull String selector) {
        Matcher m = PUSH.matcher(selector);
        if (!m.matches()) {
            return "a selector the frame paint should not send";
        }
        String id = m.group(1);
        Matcher declared = Pattern.compile("\\w+\\s+" + Pattern.quote(id) + "\\s*\\{").matcher(frame);
        if (!declared.find()) {
            return id + " is not declared";
        }
        String element = block(frame, id);
        String path = m.group(2) == null ? "" : m.group(2);
        if (path.startsWith(".Background") && !own(element).contains("Background:")) {
            return id + " carries no Background";
        }
        Matcher state = Pattern.compile("^\\.Style\\.(Default|Hovered|Pressed)\\.Background").matcher(path);
        if (state.find() && !element.contains(state.group(1) + ": (Background:")) {
            return id + "'s " + state.group(1) + " state carries no Background";
        }
        return null;
    }
}
