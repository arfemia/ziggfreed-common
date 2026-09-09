package com.ziggfreed.common.ui.hud.bar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.ziggfreed.common.ui.hud.HudPosition;
import com.ziggfreed.common.ui.hud.bar.HudBarHud.Row;

/**
 * Which live rows get a slot: the most recently moved ones up to the panel's count, whatever kind
 * they are, drawn with every row that draws a fill above every row that does not and each group in
 * its settled order, so the stack never reshuffles as different rows take the latest move. And how
 * they spread: across the columns the resolved spot opens, top-down or bottom-up by its corner.
 */
class HudBarSlotsTest {

    private static final HudPosition TOP_LEFT =
            new HudPosition(HudPosition.AnchorEdge.TOP, HudPosition.HorizontalEdge.LEFT, 16, 216);

    private static final HudPosition BOTTOM_LEFT =
            new HudPosition(HudPosition.AnchorEdge.BOTTOM, HudPosition.HorizontalEdge.LEFT, 16, 16);

    /** A row that draws a fill: it was moved with a reading. */
    private static Row fill(String id, int order, long lastMoved) {
        return new Row(id, look(id, order), null, 1, lastMoved, new HudBarReading(1, 2));
    }

    /** A row about an item: counted under the item prefix, no reading, no fill. */
    private static Row item(String id, int order, long lastMoved) {
        return new Row(HudBars.itemRowId(id), look(id, order), id, 3, lastMoved, null);
    }

    private static HudBarLook look(String id, int order) {
        return HudBarLook.resolve(id, null, new HudBarDisplay(null, null, null, order, null, null, null, null));
    }

    private static List<String> ids(List<Row> rows) {
        return rows.stream().map(Row::id).toList();
    }

    /**
     * How many slots the document under test declares. The ceiling belongs to the drawing document
     * everywhere in the real code, because two panels draw from this same logic with different
     * counts, so the tests state their own rather than reading one off a panel.
     */
    private static final int SLOTS = 4;

    /** A document of {@code columns} columns of {@code perColumn} slots; only the counts matter here. */
    private static HudBarLayout layout(int columns, int perColumn) {
        return new HudBarLayout("test", "test:hud", "Hud/Test.ui", "#Test", columns, perColumn,
                12, 200, 8, 180, TOP_LEFT);
    }

    /** A resolved spot with the two spread leaves stated; the corner is the top-left. */
    private static HudBarPlacement spread(int columns, int rowsPerColumn) {
        return new HudBarPlacement(TOP_LEFT, columns, rowsPerColumn, null);
    }

    @Test
    void theNewestMoversAreShownInSettledOrder() {
        // e moved last, then c, a, d; b is the oldest and the one that loses its slot.
        List<Row> shown = HudBarHud.choose(List.of(
                fill("a", 10, 300), fill("b", 20, 100), fill("c", 30, 400), fill("d", 40, 200), fill("e", 50, 500)), 4, SLOTS);

        assertEquals(List.of("a", "c", "d", "e"), ids(shown),
                "the four most recently moved, then sorted by order rather than by recency");
    }

    @Test
    void fillRowsSortAboveItemRowsWhateverTheirOrder() {
        List<Row> shown = HudBarHud.choose(List.of(
                item("plank", 10, 900), fill("wood", 1000, 100), item("log", 5, 500), fill("stone", 20, 300)), 4, SLOTS);

        assertEquals(List.of("stone", "wood", HudBars.itemRowId("log"), HudBars.itemRowId("plank")), ids(shown),
                "every fill row first, in order, then every item row, in order; recency and the "
                        + "item rows' lower numbers do not lift them above a fill");
    }

    @Test
    void theSlotCountIsSharedAcrossBothKinds() {
        // The item row moved most recently and takes a slot from the oldest fill row.
        List<Row> shown = HudBarHud.choose(List.of(
                fill("a", 10, 100), fill("b", 20, 200), fill("c", 30, 300), fill("d", 40, 400),
                item("plank", 1000, 500)), 4, SLOTS);

        assertEquals(List.of("b", "c", "d", HudBars.itemRowId("plank")), ids(shown),
                "one MaxVisible over both kinds: the item row is one of the four newest and the "
                        + "oldest fill row is the one left out");
    }

    @Test
    void fewerRowsThanSlotsAreAllShown() {
        List<Row> shown = HudBarHud.choose(List.of(fill("b", 20, 1), fill("a", 10, 2)), 4, SLOTS);
        assertEquals(List.of("a", "b"), ids(shown));
    }

    @Test
    void theCountIsHeldToTheDocumentsSlotsAndNeverNegative() {
        List<Row> rows = List.of(fill("a", 1, 1), fill("b", 2, 2), fill("c", 3, 3),
                fill("d", 4, 4), fill("e", 5, 5), item("f", 6, 6));

        assertEquals(SLOTS, HudBarHud.choose(rows, 99, SLOTS).size());
        assertTrue(HudBarHud.choose(rows, 0, SLOTS).isEmpty());
        assertTrue(HudBarHud.choose(rows, -3, SLOTS).isEmpty());
        assertTrue(HudBarHud.choose(List.of(), 4, SLOTS).isEmpty());
    }

    @Test
    void aTieOnRecencyIsBrokenByOrderThenId() {
        List<Row> shown = HudBarHud.choose(List.of(fill("x", 20, 7), fill("y", 10, 7), fill("z", 10, 7)), 2, SLOTS);

        assertEquals(List.of("y", "z"), ids(shown),
                "same instant: the lower order wins the slot, and equal orders fall back to the id");
    }

    @Test
    void aSpotNobodyAuthoredIsAPlainSingleColumn() {
        HudBarLayout layout = layout(2, 13);
        HudBarPlacement spot = HudBarPlacement.fallback(layout);

        assertEquals(1, HudBarHud.columnsFor(0, layout, spot), "nothing showing is still one column");
        assertEquals(1, HudBarHud.columnsFor(1, layout, spot));
        assertEquals(1, HudBarHud.columnsFor(40, layout, spot),
                "spreading sideways is something a spot is authored to do; unauthored, it stacks, "
                        + "however many columns the document happens to declare");
    }

    @Test
    void anAuthoredSpotOpensAColumnPerRowUpToWhatTheDocumentHas() {
        HudBarLayout layout = layout(3, 3);
        HudBarPlacement spot = spread(3, 1);

        assertEquals(1, HudBarHud.columnsFor(1, layout, spot), "one moving value is one bar, not a column of one");
        assertEquals(2, HudBarHud.columnsFor(2, layout, spot));
        assertEquals(3, HudBarHud.columnsFor(3, layout, spot));
        assertEquals(3, HudBarHud.columnsFor(40, layout, spot),
                "never more columns than the document declares, however many rows are moving");
        assertEquals(3, HudBarHud.columnsFor(40, layout, spread(9, 1)),
                "nor more than the document declares when the spot asks for more");
    }

    @Test
    void aTallerRowsPerColumnKeepsTheStackNarrowForLonger() {
        HudBarLayout layout = layout(2, 13);
        HudBarPlacement spot = spread(2, 13);

        assertEquals(1, HudBarHud.columnsFor(13, layout, spot), "thirteen rows still read as one column");
        assertEquals(2, HudBarHud.columnsFor(14, layout, spot), "the fourteenth opens the second column");
    }

    @Test
    void aSpotDeeperThanTheDocumentIsReadAtTheDocumentsDepth() {
        // A spot measured for the thirteen-deep ledger, picked for a three-deep block: the fourth
        // row has to open a column, because no column here can hold it.
        HudBarLayout layout = layout(6, 3);
        HudBarPlacement spot = spread(2, 13);

        assertEquals(1, HudBarHud.columnsFor(3, layout, spot));
        assertEquals(2, HudBarHud.columnsFor(4, layout, spot), "the document is only three deep");
    }

    @Test
    void rowsSplitEvenlyAndNeverPastWhatAColumnDeclares() {
        HudBarLayout layout = layout(2, 13);

        assertEquals(0, HudBarHud.rowsPerColumnFor(0, 1, layout), "nothing showing fills no slots");
        assertEquals(5, HudBarHud.rowsPerColumnFor(5, 1, layout));
        assertEquals(5, HudBarHud.rowsPerColumnFor(9, 2, layout),
                "an odd split rounds up, so the last column is the short one");
        assertEquals(13, HudBarHud.rowsPerColumnFor(40, 2, layout),
                "a column never takes more rows than it declares slots");
    }

    @Test
    void anAllowedColumnThatEndsUpEmptyIsNotCounted() {
        // Four rows across a three-column allowance split two deep, which fills two columns and
        // leaves the third with nothing in it: the panel must be two columns wide, not three.
        assertEquals(2, HudBarHud.usedColumnsFor(4, 2));
        assertEquals(3, HudBarHud.usedColumnsFor(9, 3));
        assertEquals(1, HudBarHud.usedColumnsFor(1, 1));
        assertEquals(0, HudBarHud.usedColumnsFor(0, 3), "nothing showing fills no columns");
        assertEquals(0, HudBarHud.usedColumnsFor(5, 0), "no depth fills no columns");
    }

    @Test
    void theSlotCapStopsAtWhatTheSpotsColumnsCanHold() throws Exception {
        // A six-column document at a two-column spot: eighteen slots exist, six are reachable, and
        // a seventh row would otherwise open a column the spot never allowed.
        HudBarLayout layout = layout(6, 3);

        assertEquals(6, HudBarHud.slotCap(HudBarPanelAsset.defaults(), spread(2, 1), layout));
        assertEquals(18, HudBarHud.slotCap(HudBarPanelAsset.defaults(), spread(6, 1), layout));
        assertEquals(4, HudBarHud.slotCap(HudBarPanelAssetTest.panel("{ \"MaxVisible\": 4 }", "grid", null, null),
                spread(6, 1), layout), "an authored MaxVisible still caps it lower");
    }

    @Test
    void aBottomPinnedColumnFillsFromTheBottom() {
        // Three slots deep, two rows in use: top-down the first row takes slot 0; bottom-up it
        // takes the LAST used slot, so the row already on screen stays against the pinned edge and
        // the next opens above it.
        assertEquals(0, HudBarHud.ordinalInColumn(0, 2, false));
        assertEquals(1, HudBarHud.ordinalInColumn(1, 2, false));
        assertEquals(1, HudBarHud.ordinalInColumn(0, 2, true), "the top used slot draws the second row");
        assertEquals(0, HudBarHud.ordinalInColumn(1, 2, true), "the bottom used slot draws the first");
        assertTrue(new HudBarPlacement(BOTTOM_LEFT, 2, 1, null).bottomUp());
        assertTrue(!new HudBarPlacement(TOP_LEFT, 2, 1, null).bottomUp());
    }

    @Test
    void aReadingFractionIsHeldToTheBarAndFullWithNoCeiling() {
        assertEquals(0.5, new HudBarReading(1, 2).fraction(), 1e-9);
        assertEquals(1.0, new HudBarReading(5, 2).fraction(), 1e-9, "past the ceiling draws full");
        assertEquals(0.0, new HudBarReading(-1, 2).fraction(), 1e-9, "below zero draws empty");
        assertEquals(1.0, new HudBarReading(0, 0).fraction(), 1e-9, "no ceiling left draws full rather than dividing by nothing");
        assertEquals(1.0, HudBarReading.FULL.fraction(), 1e-9);
    }
}
