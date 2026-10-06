package com.ziggfreed.common.ui.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

import org.junit.jupiter.api.Test;

import com.ziggfreed.common.ui.theme.Palette;

/**
 * The menu's palette is read, never shared: {@link Palette}'s slots are public fields, so a caller that
 * changed the one instance {@link MenuDeps#EMPTY} held would recolour the rail for every server on the
 * default, and a consumer that kept changing its own palette after handing it over would repaint menus
 * it never meant to.
 */
class MenuDepsTest {

    @Test
    void theDefaultPaletteCannotBeRecolouredThroughWhatItHandsOut() {
        String muted = MenuDeps.EMPTY.palette().textMuted;

        MenuDeps.EMPTY.palette().textMuted = "#ff00ff";

        assertEquals(muted, MenuDeps.EMPTY.palette().textMuted);
        assertNotSame(MenuDeps.EMPTY.palette(), MenuDeps.EMPTY.palette());
    }

    @Test
    void aConsumersPaletteIsTakenAsItWasWhenTheMenuWasBuilt() {
        Palette mine = new Palette("#112233", "#445566", "#778899");
        mine.textureDir = "Themes/Mine";
        mine.frameBorder = 12;

        MenuDeps deps = MenuDeps.builder().palette(mine).build();
        mine.accent = "#000000";

        Palette read = deps.palette();
        assertEquals("#445566", read.accent);
        assertEquals("#112233", read.primary);
        assertEquals("Themes/Mine", read.textureDir);
        assertEquals(12, read.frameBorder);
    }

    @Test
    void aCopyCarriesEverySlot() {
        Palette full = new Palette("#010101", "#020202", "#030303");
        full.frame = "#040404";
        full.header = "#050505";
        full.divider = "#060606";
        full.buttonNeutral = "#070707";
        full.buttonPositive = "#080808";
        full.buttonClaim = "#090909";
        full.buttonDestructive = "#0a0a0a";
        full.textPrimary = "#0b0b0b";
        full.textMuted = "#0c0c0c";
        full.textureDir = "Dir";
        full.frameBorder = 7;
        full.panelBorder = 3;

        Palette copy = full.copy();

        assertNotSame(full, copy);
        assertEquals(full.primary, copy.primary);
        assertEquals(full.accent, copy.accent);
        assertEquals(full.background, copy.background);
        assertEquals(full.frame, copy.frame);
        assertEquals(full.header, copy.header);
        assertEquals(full.divider, copy.divider);
        assertEquals(full.buttonNeutral, copy.buttonNeutral);
        assertEquals(full.buttonPositive, copy.buttonPositive);
        assertEquals(full.buttonClaim, copy.buttonClaim);
        assertEquals(full.buttonDestructive, copy.buttonDestructive);
        assertEquals(full.textPrimary, copy.textPrimary);
        assertEquals(full.textMuted, copy.textMuted);
        assertEquals(full.textureDir, copy.textureDir);
        assertEquals(full.frameBorder, copy.frameBorder);
        assertEquals(full.panelBorder, copy.panelBorder);
    }
}
