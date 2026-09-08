package com.ziggfreed.common.ui.hud.bar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.ziggfreed.common.ui.hud.bar.HudBarHud.Row;
import com.ziggfreed.common.ui.hud.bar.HudBarSource.Reading;

/**
 * Which live bars get a slot: the most recently moved ones up to the panel's count, drawn in their
 * authored order so the stack never reshuffles as different bars take the latest move.
 */
class HudBarSlotsTest {

    private static HudBarAsset bar(String id, int order) throws IOException {
        return HudBarAssetCodecTest.bar("{ \"Source\": \"t:" + id + "\", \"Order\": " + order + " }", id, null, null);
    }

    private static Row row(HudBarAsset bar, long lastMoved) {
        return new Row(bar, 1, lastMoved, new Reading(1, 2));
    }

    @Test
    void theNewestMoversAreShownInAuthoredOrder() throws Exception {
        HudBarAsset first = bar("a", 10);
        HudBarAsset second = bar("b", 20);
        HudBarAsset third = bar("c", 30);
        HudBarAsset fourth = bar("d", 40);
        HudBarAsset fifth = bar("e", 50);

        // e moved last, then c, a, d; b is the oldest and the one that loses its slot.
        List<Row> shown = HudBarHud.choose(List.of(
                row(first, 300), row(second, 100), row(third, 400), row(fourth, 200), row(fifth, 500)), 4);

        assertEquals(List.of("a", "c", "d", "e"), shown.stream().map(r -> r.bar().getId()).toList(),
                "the four most recently moved, then sorted by Order rather than by recency");
    }

    @Test
    void fewerRowsThanSlotsAreAllShown() throws Exception {
        List<Row> shown = HudBarHud.choose(List.of(row(bar("b", 20), 1), row(bar("a", 10), 2)), 4);
        assertEquals(List.of("a", "b"), shown.stream().map(r -> r.bar().getId()).toList());
    }

    @Test
    void theCountIsHeldToTheDocumentsSlotsAndNeverNegative() throws Exception {
        List<Row> rows = List.of(row(bar("a", 1), 1), row(bar("b", 2), 2), row(bar("c", 3), 3),
                row(bar("d", 4), 4), row(bar("e", 5), 5), row(bar("f", 6), 6));

        assertEquals(HudBarPanelAsset.MAX_SLOTS, HudBarHud.choose(rows, 99).size());
        assertTrue(HudBarHud.choose(rows, 0).isEmpty());
        assertTrue(HudBarHud.choose(rows, -3).isEmpty());
        assertTrue(HudBarHud.choose(List.of(), 4).isEmpty());
    }

    @Test
    void aTieOnRecencyIsBrokenByOrderThenId() throws Exception {
        HudBarAsset x = bar("x", 20);
        HudBarAsset y = bar("y", 10);
        HudBarAsset z = bar("z", 10);

        List<Row> shown = HudBarHud.choose(List.of(row(x, 7), row(y, 7), row(z, 7)), 2);

        assertEquals(List.of("y", "z"), shown.stream().map(r -> r.bar().getId()).toList(),
                "same instant: the lower Order wins the slot, and equal Orders fall back to the id");
    }

    @Test
    void aReadingFractionIsHeldToTheBarAndFullWithNoCeiling() {
        assertEquals(0.5, new Reading(1, 2).fraction(), 1e-9);
        assertEquals(1.0, new Reading(5, 2).fraction(), 1e-9, "past the ceiling draws full");
        assertEquals(0.0, new Reading(-1, 2).fraction(), 1e-9, "below zero draws empty");
        assertEquals(1.0, new Reading(0, 0).fraction(), 1e-9, "no ceiling left draws full rather than dividing by nothing");
        assertEquals(1.0, Reading.FULL.fraction(), 1e-9);
    }
}
