package com.ziggfreed.common.ui.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Locale;

import org.junit.jupiter.api.Test;

import com.ziggfreed.common.ui.theme.Palette;

/**
 * The shared menu's one palette: its defaults are vanilla's own rail colours, a document's alpha spelling
 * converts to Java's one way, a translucent fill is judged over what it covers, the default keeps every text
 * colour readable on the fill it sits on (WCAG's ratio, 4.5:1 for text and 3:1 for the selected tab's bar),
 * a palette with gaps falls back slot by slot, and the default leaves the frame as authored. The documents
 * are held to the same defaults (ZigMenuDocumentsTest).
 */
class MenuPaletteTest {

    @Test
    void theFormulaReadsTheKnownEnds() {
        assertEquals(21.0, MenuPalette.contrast("#000000", "#ffffff"), 0.01);
        assertEquals(1.0, MenuPalette.contrast("#1a2633", "#1a2633"), 1e-9);
        assertEquals(MenuPalette.contrast("#96a9be", "#131c28"), MenuPalette.contrast("#131c28", "#96a9be"), 1e-9,
                "the ratio does not care which colour is in front");
    }

    @Test
    void theDefaultsAreVanillasOwnRailColours() {
        assertEquals("#96a9be", MenuPalette.TEXT_MUTED, "Common.ui @ColorDefaultLabel");
        assertEquals("#ffffff", MenuPalette.TEXT_PRIMARY, "Common.ui @ColorDefault");
        assertEquals(MenuPalette.fromMarkup("#000000(0.15)"), MenuPalette.BACKGROUND, "vanilla's pane");
        assertEquals(MenuPalette.fromMarkup("#000000(0.2)"), MenuPalette.HEADER, "Common.ui @ColorSimpleButtonBackground");
        assertEquals("#2b3542", MenuPalette.DIVIDER, "Common.ui @ContentSeparator");
        assertEquals("#e8a93b", MenuPalette.ACCENT, "Common.ui @ColorGoldHighlight");
        assertEquals(MenuPalette.fromMarkup("#000000(0)"), MenuPalette.CLEAR, "no fill");
    }

    @Test
    void aDocumentsAlphaSpellingIsJavasOneWay() {
        assertEquals("#00000026", MenuPalette.fromMarkup("#000000(0.15)"), "round(0.15 x 255) = 38 = 0x26");
        assertEquals("#00000033", MenuPalette.fromMarkup("#000000(0.2)"), "round(0.2 x 255) = 51 = 0x33");
        assertEquals("#00000000", MenuPalette.fromMarkup("#000000(0)"));
        assertEquals("#2b3542", MenuPalette.fromMarkup("#2B3542"), "an opaque colour is itself, lower-cased");
    }

    @Test
    void aTranslucentFillIsJudgedOverWhatItCovers() {
        assertEquals("#121a26", MenuPalette.over("#00000033", "#16212f"), "20% black over the frame body");
        assertEquals("#e8a93b", MenuPalette.over("#e8a93b", "#16212f"), "an opaque colour covers what is under it");
        assertEquals("#e8b962", MenuPalette.multiply("#ffffff", "#e8b962"), "white under the mask is the mask");

        Palette opaque = MenuPalette.defaults();
        opaque.background = "#160a08";
        MenuPalette.Pair label = MenuPalette.pairs(MenuPalette.resolve(opaque)).get(0);
        assertEquals("#160a08", label.background(), "an opaque pane is judged as itself");
    }

    @Test
    void theDefaultPaletteClearsEveryFloor() {
        for (MenuPalette.Pair pair : MenuPalette.pairs(MenuPalette.resolve(MenuPalette.defaults()))) {
            double ratio = MenuPalette.contrast(pair.foreground(), pair.background());
            assertTrue(ratio >= pair.floor(), pair.what() + " reads "
                    + String.format(Locale.ROOT, "%.2f", ratio) + ":1, under its " + pair.floor() + ":1 floor");
        }
    }

    @Test
    void aPaletteWithGapsFallsBackSlotBySlot() {
        Palette partial = new Palette(null, "#ff5a2a", null);
        partial.textMuted = "not a colour";
        partial.header = "#2a141080";
        partial.textPrimary = "#ffffff80";

        MenuPalette.Resolved resolved = MenuPalette.resolve(partial);

        assertEquals("#ff5a2a", resolved.accent(), "a slot the palette fills is read");
        assertEquals("#2a141080", resolved.header(), "a fill takes an alpha");
        assertEquals(MenuPalette.TEXT_PRIMARY, resolved.textPrimary(), "text is opaque, so an alpha text colour is the default's");
        assertEquals(MenuPalette.BACKGROUND, resolved.background(), "an unset slot is the default's");
        assertEquals(MenuPalette.TEXT_MUTED, resolved.textMuted(), "a malformed slot is the default's");
        assertEquals(MenuPalette.DIVIDER, resolved.divider());
        assertEquals(MenuPalette.resolve(MenuPalette.defaults()), MenuPalette.resolve(null), "no palette is the default");
    }

    @Test
    void theDefaultPaletteLeavesTheFrameAsAuthored() {
        assertFalse(MenuFrameRetint.paints(MenuPalette.defaults()), "no tint and no texture set");

        Palette tinted = MenuPalette.defaults();
        tinted.frame = "#b5532a";
        assertTrue(MenuFrameRetint.paints(tinted));

        Palette textured = MenuPalette.defaults();
        textured.textureDir = "Common/Molten/";
        assertTrue(MenuFrameRetint.paints(textured));
    }
}
