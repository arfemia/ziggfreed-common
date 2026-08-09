package com.ziggfreed.common.ui.hud;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Guards the config-facing preset parse for the HUD layout: every named corner maps to the right
 * edge pair, unknown presets return {@code null} (the caller falls back to the HUD's default),
 * and parsing is case/whitespace/underscore tolerant (the value comes from a hand-authored owner
 * file or a typed command) so the preferred PascalCase spelling ({@code TopLeft}) and the legacy
 * SCREAMING_SNAKE alias ({@code TOP_LEFT}) both resolve. {@code toAnchor} itself touches engine
 * {@code Anchor}/{@code Value} types and is covered by the build; the edge-pinning CONTRACT it
 * implements is documented on the class.
 */
class HudPositionTest {

    @Test
    void namedPresetsMapToEdgePairs() {
        HudPosition topLeft = HudPosition.parse("TopLeft", 16, 90);
        assertNotNull(topLeft);
        assertEquals(HudPosition.AnchorEdge.TOP, topLeft.getAnchorEdge());
        assertEquals(HudPosition.HorizontalEdge.LEFT, topLeft.getHorizontalEdge());
        assertEquals(16, topLeft.getOffsetX());
        assertEquals(90, topLeft.getOffsetY());

        HudPosition bottomRight = HudPosition.parse("BottomRight", 24, 160);
        assertNotNull(bottomRight);
        assertEquals(HudPosition.AnchorEdge.BOTTOM, bottomRight.getAnchorEdge());
        assertEquals(HudPosition.HorizontalEdge.RIGHT, bottomRight.getHorizontalEdge());

        HudPosition center = HudPosition.parse("Center", 0, 0);
        assertNotNull(center);
        assertEquals(HudPosition.AnchorEdge.CENTER, center.getAnchorEdge());
        assertEquals(HudPosition.HorizontalEdge.CENTER, center.getHorizontalEdge());

        HudPosition topCenter = HudPosition.parse("TopCenter", 0, 64);
        assertNotNull(topCenter);
        assertEquals(HudPosition.AnchorEdge.TOP, topCenter.getAnchorEdge());
        assertEquals(HudPosition.HorizontalEdge.CENTER, topCenter.getHorizontalEdge());
    }

    @Test
    void fullRosterResolvesForBothSpellings() {
        String[] pascalCase = {
            "TopLeft", "TopCenter", "TopRight",
            "CenterLeft", "Center", "CenterRight",
            "BottomLeft", "BottomCenter", "BottomRight"
        };
        String[] screamingSnake = {
            "TOP_LEFT", "TOP_CENTER", "TOP_RIGHT",
            "CENTER_LEFT", "CENTER", "CENTER_RIGHT",
            "BOTTOM_LEFT", "BOTTOM_CENTER", "BOTTOM_RIGHT"
        };
        for (int i = 0; i < pascalCase.length; i++) {
            HudPosition fromPascal = HudPosition.parse(pascalCase[i], 0, 0);
            HudPosition fromSnake = HudPosition.parse(screamingSnake[i], 0, 0);
            assertNotNull(fromPascal, pascalCase[i] + " should resolve");
            assertNotNull(fromSnake, screamingSnake[i] + " should resolve");
            assertEquals(fromSnake.getAnchorEdge(), fromPascal.getAnchorEdge(), pascalCase[i]);
            assertEquals(fromSnake.getHorizontalEdge(), fromPascal.getHorizontalEdge(), pascalCase[i]);
        }
    }

    @Test
    void parseIsCaseAndWhitespaceTolerant() {
        assertNotNull(HudPosition.parse("top_left", 0, 0), "lower-case with underscore accepted");
        assertNotNull(HudPosition.parse("  Center_Right  ", 0, 0), "mixed case + padding accepted");
        assertNotNull(HudPosition.parse("topleft", 0, 0), "lower-case no-underscore accepted");
        assertNotNull(HudPosition.parse("  TopRight  ", 0, 0), "PascalCase + padding accepted");
    }

    @Test
    void unknownPresetsReturnNull() {
        assertNull(HudPosition.parse("MIDDLE", 0, 0));
        assertNull(HudPosition.parse("", 0, 0));
        assertNull(HudPosition.parse(null, 0, 0));
        assertFalse(HudPosition.isValidPreset("NOWHERE"));
        assertTrue(HudPosition.isValidPreset("BOTTOM_CENTER"));
        assertTrue(HudPosition.isValidPreset("BottomCenter"));
    }
}
