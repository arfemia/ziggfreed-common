package com.ziggfreed.common.ui.hud.bar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.ziggfreed.common.ui.hud.bar.HudBarHud.Row;
import com.ziggfreed.common.ui.hud.bar.HudBarSource.Reading;

/**
 * Which live rows get a slot: the most recently moved ones up to the panel's count, whatever kind
 * they are, drawn with every row that draws a fill above every row that does not and each group in
 * its settled order, so the stack never reshuffles as different rows take the latest move.
 */
class HudBarSlotsTest {

    /** A row that draws a fill: its id is a value id and it carries a reading. */
    private static Row fill(String id, int order, long lastMoved) {
        return new Row("t:" + id, look(id, order), null, 1, lastMoved, new Reading(1, 2));
    }

    /** A row about an item: counted under the item prefix, no reading, no fill. */
    private static Row item(String id, int order, long lastMoved) {
        return new Row(HudBars.itemRowId(id), look(id, order), id, 3, lastMoved, null);
    }

    private static HudBarLook look(String id, int order) {
        return HudBarLook.resolve(id, null, new HudBarDisplay(null, null, null, order, null));
    }

    private static List<String> ids(List<Row> rows) {
        return rows.stream().map(Row::id).toList();
    }

    @Test
    void theNewestMoversAreShownInSettledOrder() {
        // e moved last, then c, a, d; b is the oldest and the one that loses its slot.
        List<Row> shown = HudBarHud.choose(List.of(
                fill("a", 10, 300), fill("b", 20, 100), fill("c", 30, 400), fill("d", 40, 200), fill("e", 50, 500)), 4);

        assertEquals(List.of("t:a", "t:c", "t:d", "t:e"), ids(shown),
                "the four most recently moved, then sorted by order rather than by recency");
    }

    @Test
    void fillRowsSortAboveItemRowsWhateverTheirOrder() {
        List<Row> shown = HudBarHud.choose(List.of(
                item("plank", 10, 900), fill("wood", 1000, 100), item("log", 5, 500), fill("stone", 20, 300)), 4);

        assertEquals(List.of("t:stone", "t:wood", HudBars.itemRowId("log"), HudBars.itemRowId("plank")), ids(shown),
                "every fill row first, in order, then every item row, in order; recency and the "
                        + "item rows' lower numbers do not lift them above a fill");
    }

    @Test
    void theSlotCountIsSharedAcrossBothKinds() {
        // The item row moved most recently and takes a slot from the oldest fill row.
        List<Row> shown = HudBarHud.choose(List.of(
                fill("a", 10, 100), fill("b", 20, 200), fill("c", 30, 300), fill("d", 40, 400),
                item("plank", 1000, 500)), 4);

        assertEquals(List.of("t:b", "t:c", "t:d", HudBars.itemRowId("plank")), ids(shown),
                "one MaxVisible over both kinds: the item row is one of the four newest and the "
                        + "oldest fill row is the one left out");
    }

    @Test
    void fewerRowsThanSlotsAreAllShown() {
        List<Row> shown = HudBarHud.choose(List.of(fill("b", 20, 1), fill("a", 10, 2)), 4);
        assertEquals(List.of("t:a", "t:b"), ids(shown));
    }

    @Test
    void theCountIsHeldToTheDocumentsSlotsAndNeverNegative() {
        List<Row> rows = List.of(fill("a", 1, 1), fill("b", 2, 2), fill("c", 3, 3),
                fill("d", 4, 4), fill("e", 5, 5), item("f", 6, 6));

        assertEquals(HudBarPanelAsset.MAX_SLOTS, HudBarHud.choose(rows, 99).size());
        assertTrue(HudBarHud.choose(rows, 0).isEmpty());
        assertTrue(HudBarHud.choose(rows, -3).isEmpty());
        assertTrue(HudBarHud.choose(List.of(), 4).isEmpty());
    }

    @Test
    void aTieOnRecencyIsBrokenByOrderThenId() {
        List<Row> shown = HudBarHud.choose(List.of(fill("x", 20, 7), fill("y", 10, 7), fill("z", 10, 7)), 2);

        assertEquals(List.of("t:y", "t:z"), ids(shown),
                "same instant: the lower order wins the slot, and equal orders fall back to the id");
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
