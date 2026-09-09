package com.ziggfreed.common.ui.hud;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * What a player said about their HUD survives a save: the picks and the hidden set pack into the
 * one string each leaf carries and unpack to the same answer, an id the format cannot hold is
 * refused rather than corrupting the entries after it, the three knobs stay orthogonal, and every
 * write says whether it changed anything so a no-op never announces itself.
 */
class HudPreferenceComponentTest {

    @Test
    void picksAndHiddenPanelsRoundTripThroughThePackedLeaves() {
        HudPreferenceComponent prefs = new HudPreferenceComponent();
        prefs.setPlacement("Grid", "BottomLeft");
        prefs.setPlacement("default", "TopLeft");
        prefs.setHidden("GRID", true);

        String picks = HudPreferenceComponent.serializePlacements(prefs.placements);
        String hidden = HudPreferenceComponent.serializeSet(prefs.hidden);

        assertEquals("default=topleft|grid=bottomleft", picks, "lower-cased, sorted, packed");
        assertEquals("grid", hidden);
        assertEquals(Map.of("default", "topleft", "grid", "bottomleft"),
                HudPreferenceComponent.deserializePlacements(picks));
        assertEquals(Set.of("grid"), HudPreferenceComponent.deserializeSet(hidden));
        assertEquals("", HudPreferenceComponent.serializePlacements(Map.of()));
        assertTrue(HudPreferenceComponent.deserializePlacements(null).isEmpty());
        assertTrue(HudPreferenceComponent.deserializePlacements("garbage|=x|y=").isEmpty(),
                "an entry with no panel or no spot is skipped, never half-read");
    }

    @Test
    void anIdTheFormatReservesIsRefused() {
        HudPreferenceComponent prefs = new HudPreferenceComponent();

        assertFalse(prefs.setPlacement("grid", "a|b"));
        assertFalse(prefs.setPlacement("a=b", "topleft"));
        assertFalse(prefs.setHidden("x|y", true));
        assertTrue(prefs.placements.isEmpty());
        assertTrue(prefs.hidden.isEmpty());
        assertTrue(HudPreferenceComponent.usesReservedDelimiter(""));
        assertTrue(HudPreferenceComponent.usesReservedDelimiter(null));
    }

    @Test
    void everyWriteSaysWhetherItChangedAnything() {
        HudPreferenceComponent prefs = new HudPreferenceComponent();

        assertTrue(prefs.setPlacement("grid", "TopRight"));
        assertFalse(prefs.setPlacement("GRID", "topright"), "the same pick, whatever the case");
        assertTrue(prefs.setPlacement("grid", "bottomleft"));
        assertEquals("bottomleft", prefs.placementOf("Grid"));
        assertTrue(prefs.setPlacement("grid", null), "clearing a pick is a change");
        assertFalse(prefs.setPlacement("grid", ""), "clearing nothing is not");
        assertNull(prefs.placementOf("grid"));

        assertTrue(prefs.setHidden("grid", true));
        assertFalse(prefs.setHidden("grid", true));
        assertTrue(prefs.setHidden("grid", false));
        assertFalse(prefs.setHidden("grid", false));

        assertTrue(prefs.setHideAll(true));
        assertFalse(prefs.setHideAll(true));
        assertTrue(prefs.setHideAll(false));
    }

    @Test
    void hidingEverythingAndHidingOnePanelAreOrthogonal() {
        HudPreferenceComponent prefs = new HudPreferenceComponent();
        prefs.setHidden("grid", true);

        assertTrue(prefs.isHidden("grid"));
        assertFalse(prefs.isHidden("default"));
        prefs.setHideAll(true);
        assertTrue(prefs.isHidden("default"), "hide-all covers a panel nobody hid on its own");
        assertFalse(prefs.isHiddenAlone("default"), "but does not mark it as hidden on its own");
        assertTrue(prefs.isHiddenAlone("grid"));
        prefs.setHideAll(false);
        assertTrue(prefs.isHidden("grid"), "the per-panel switch survives hide-all going off");
        assertFalse(prefs.isHidden("default"));
    }

    @Test
    void aCloneIsIndependent() {
        HudPreferenceComponent prefs = new HudPreferenceComponent();
        prefs.setPlacement("grid", "topright");
        prefs.setHidden("default", true);
        prefs.setHideAll(true);

        HudPreferenceComponent copy = prefs.clone();
        copy.setPlacement("grid", null);
        copy.setHidden("default", false);
        copy.setHideAll(false);

        assertEquals("topright", prefs.placementOf("grid"));
        assertTrue(prefs.isHiddenAlone("default"));
        assertTrue(prefs.hideAll());
    }
}
