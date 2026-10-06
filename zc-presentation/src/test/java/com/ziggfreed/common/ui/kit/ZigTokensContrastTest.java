package com.ziggfreed.common.ui.kit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.ziggfreed.common.ui.UiRetint;
import com.ziggfreed.common.ui.menu.MenuPalette;

/**
 * The kit's colours stay readable: every ink and every tone's text at 4.5:1 (WCAG AA) on every surface a row or pane
 * can show (pane, row, hovered, pressed, the scrim over a row), bright ink on the selected row, every tone's fill at
 * 3:1 as a mark on the row at rest and hovered; a translucent surface judged over what it covers, the frame body,
 * as {@code MenuPaletteTest} judges the rail. The Java mirror agrees with itself (each constant is its markup
 * spelling converted) and an authored accent is clamped to the mark floor.
 */
class ZigTokensContrastTest {

    @Test
    void everyInkAndToneTextReadsOnEverySurface() {
        StringBuilder failures = new StringBuilder();
        for (Map.Entry<String, String> ink : texts().entrySet()) {
            for (Map.Entry<String, String> surface : surfaces().entrySet()) {
                double ratio = MenuPalette.contrast(ink.getValue(), surface.getValue());
                if (ratio < ZigTokens.TEXT_FLOOR) {
                    failures.append(String.format(Locale.ROOT, "%n%s on %s reads %.2f:1", ink.getKey(),
                            surface.getKey(), ratio));
                }
            }
        }
        assertTrue(failures.isEmpty(), "under 4.5:1:" + failures);
    }

    @Test
    void brightInkReadsOnTheSelectedRow() {
        double ratio = MenuPalette.contrast(ZigTokens.INK_BRIGHT, ZigTokens.SURFACE_ROW_SELECTED);
        assertTrue(ratio >= ZigTokens.TEXT_FLOOR, String.format(Locale.ROOT, "%.2f:1", ratio));
    }

    @Test
    void everyToneFillMarksTheRow() {
        String row = ZigTokens.rowSeen();
        String hover = ZigTokens.seenOver(ZigTokens.SURFACE_ROW_HOVER, ZigTokens.FRAME_BODY);
        StringBuilder failures = new StringBuilder();
        for (Tone tone : Tone.values()) {
            String fill = tone.fillHex();
            if (fill == null) {
                continue;
            }
            for (String surface : List.of(row, hover)) {
                double ratio = MenuPalette.contrast(fill, surface);
                if (ratio < ZigTokens.MARK_FLOOR) {
                    failures.append(String.format(Locale.ROOT, "%n%s fill %s on %s reads %.2f:1", tone, fill,
                            surface, ratio));
                }
            }
        }
        assertTrue(failures.isEmpty(), "under 3:1:" + failures);
    }

    @Test
    void theUntunedFillsFailedTheMarkFloor() {
        // The plan's starting fills for Waiting and Blocked, before they were lifted inside their hue.
        assertTrue(MenuPalette.contrast("#6f5a8e", ZigTokens.rowSeen()) < ZigTokens.MARK_FLOOR);
        assertTrue(MenuPalette.contrast("#4a5a6a", ZigTokens.rowSeen()) < ZigTokens.MARK_FLOOR);
    }

    @Test
    void eachConstantIsItsMarkupSpelling() {
        Map<String, String> colours = ZigTokens.colours();
        assertEquals(MenuPalette.fromMarkup(colours.get("ZigSurfaceRow")), ZigTokens.SURFACE_ROW);
        assertEquals("#1019258c", ZigTokens.SURFACE_ROW, "round(0.55 x 255) = 140 = 0x8c");
        assertEquals("#101925(0.55)", colours.get("ZigSurfaceRow"));
        for (Map.Entry<String, String> colour : colours.entrySet()) {
            assertTrue(colour.getKey().startsWith("Zig"), colour.getKey());
            assertTrue(UiRetint.isHex(MenuPalette.fromMarkup(colour.getValue())), colour.getKey());
        }
        assertEquals(31, colours.size(), "15 inks and surfaces, 16 tone colours");
        assertEquals(Integer.valueOf(56), ZigTokens.integers().get("ZigRowHeight"));
        assertEquals(Integer.valueOf(64), ZigTokens.integers().get("ZigPicLarge"));
    }

    @Test
    void eachToneReadsItsOwnTokens() {
        assertEquals(ZigTokens.INK_BODY, Tone.NEUTRAL.textHex());
        assertEquals(null, Tone.NEUTRAL.fillHex(), "no state, no accent");
        Map<String, String> colours = ZigTokens.colours();
        for (Tone tone : Tone.values()) {
            if (tone == Tone.NEUTRAL) {
                continue;
            }
            String name = "ZigTone" + tone.name().charAt(0) + tone.name().substring(1).toLowerCase(Locale.ROOT);
            assertEquals(colours.get(name + "Fill"), tone.fillHex(), name);
            assertEquals(colours.get(name + "Text"), tone.textHex(), name);
        }
    }

    @Test
    void aSeenColourBlendsByItsAlpha() {
        assertEquals("#121a26", ZigTokens.seenOver("#00000033", "#16212f"), "MenuPaletteTest's own case");
        assertEquals("#e8a93b", ZigTokens.seenOver("#E8A93B", "#16212f"), "opaque is itself");
    }

    @Test
    void anAuthoredAccentIsClampedToTheMarkFloor() {
        assertEquals("#e05a2a", ZigTokens.clampAccent("#E05A2A"), "readable: kept, lower-cased");
        assertEquals(ZigTokens.ACCENT, ZigTokens.clampAccent("#101010"), "too dark for the row");
        assertEquals(ZigTokens.ACCENT, ZigTokens.clampAccent("orange"));
        assertEquals(ZigTokens.ACCENT, ZigTokens.clampAccent(null));
        assertEquals(ZigTokens.ACCENT, ZigTokens.clampAccent("#e05a2a80"), "an alpha accent is not a mark");
    }

    @Test
    void aWordOnAFillTakesTheInkThatReads() {
        assertEquals(ZigTokens.INK_DARK, ZigTokens.inkOn(ZigTokens.ACCENT), "gold takes dark ink");
        assertEquals(ZigTokens.INK_BRIGHT, ZigTokens.inkOn(ZigTokens.SURFACE_ROW_SELECTED), "steel blue takes white");
        assertTrue(MenuPalette.contrast(ZigTokens.inkOn(ZigTokens.ACCENT), ZigTokens.ACCENT) >= ZigTokens.TEXT_FLOOR);
    }

    /** Every text colour: the inks, the accent, and each tone's word. */
    private static Map<String, String> texts() {
        Map<String, String> texts = new LinkedHashMap<>();
        texts.put("ink bright", ZigTokens.INK_BRIGHT);
        texts.put("ink strong", ZigTokens.INK_STRONG);
        texts.put("ink body", ZigTokens.INK_BODY);
        texts.put("ink muted", ZigTokens.INK_MUTED);
        texts.put("ink faint", ZigTokens.INK_FAINT);
        texts.put("ink section", ZigTokens.INK_SECTION);
        texts.put("ink heading", ZigTokens.INK_HEADING);
        texts.put("accent", ZigTokens.ACCENT);
        for (Tone tone : Tone.values()) {
            texts.put(tone + " text", tone.textHex());
        }
        return texts;
    }

    /** Every surface text sits on, as it reads over the frame body (the scrim over a row). */
    private static Map<String, String> surfaces() {
        String body = ZigTokens.FRAME_BODY;
        String row = ZigTokens.seenOver(ZigTokens.SURFACE_ROW, body);
        Map<String, String> surfaces = new LinkedHashMap<>();
        surfaces.put("pane", ZigTokens.seenOver(ZigTokens.SURFACE_PANE, body));
        surfaces.put("row", row);
        surfaces.put("hovered row", ZigTokens.seenOver(ZigTokens.SURFACE_ROW_HOVER, body));
        surfaces.put("pressed row", ZigTokens.seenOver(ZigTokens.SURFACE_ROW_PRESSED, body));
        surfaces.put("scrim", ZigTokens.seenOver(ZigTokens.SURFACE_SCRIM, row));
        return surfaces;
    }
}
