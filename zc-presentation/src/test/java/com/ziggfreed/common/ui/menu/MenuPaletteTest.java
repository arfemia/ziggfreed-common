package com.ziggfreed.common.ui.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Locale;

import org.junit.jupiter.api.Test;

import com.ziggfreed.common.ui.theme.Palette;

/**
 * The shared menu's one palette: the library's default keeps every text colour readable on the fill it
 * sits on (WCAG's ratio, 4.5:1 for text and 3:1 for the selected tab's marker), a palette with gaps falls
 * back slot by slot so a three-colour palette still paints a whole rail, and the default leaves the frame
 * as authored. The documents are held to the same default values (ZigMenuDocumentsTest).
 */
class MenuPaletteTest {

    @Test
    void theFormulaReadsTheKnownEnds() {
        assertEquals(21.0, MenuPalette.contrast("#000000", "#ffffff"), 0.01);
        assertEquals(1.0, MenuPalette.contrast("#1a2633", "#1a2633"), 1e-9);
        assertEquals(MenuPalette.contrast("#b6c9de", "#0a1119"), MenuPalette.contrast("#0a1119", "#b6c9de"), 1e-9,
                "the ratio does not care which colour is in front");
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

        MenuPalette.Resolved resolved = MenuPalette.resolve(partial);

        assertEquals("#ff5a2a", resolved.accent(), "a slot the palette fills is read");
        assertEquals(MenuPalette.BACKGROUND, resolved.background(), "an unset slot is the default's");
        assertEquals(MenuPalette.TEXT_MUTED, resolved.textMuted(), "a malformed slot is the default's");
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
