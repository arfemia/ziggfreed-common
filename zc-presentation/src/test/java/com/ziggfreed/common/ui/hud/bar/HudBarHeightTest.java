package com.ziggfreed.common.ui.hud.bar;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.junit.jupiter.api.Test;

import com.ziggfreed.common.ui.hud.HudPosition;
import com.ziggfreed.common.ui.hud.bar.HudBarHud.ColumnShape;
import com.ziggfreed.common.ui.hud.bar.HudBarHud.Row;

/**
 * How tall the panel is pushed, where its band falls, and where each column sits inside it. The
 * panel is an absolutely anchored box with no content to size to, so the height has to add up
 * EXACTLY from the document's numbers: a row with a fill carries the bar block and a row about an
 * item does not, the tallest used column decides, the band adds to a column that carries one, the
 * spot's floor holds a short panel up, and a column whose spot cuts cells out of its pinned end
 * reserves them below its first row and never stands taller than the columns without a cut: what
 * it has no room for spills into a row above the block, filled from the first column outward
 * ({@link HudBarSpread}, whose dealing is pinned in {@code HudBarSpreadTest}; here its consequences
 * in pixels). The band is one slot's taller margin, so which slot carries it is worked out here in
 * both directions a column can fill, with and without a cut; and every column is placed by a push
 * on its first visible slot, so a bottom-pinned column shorter than the tallest bottom-aligns and a
 * top-pinned one keeps its cut at the top. Every number below is the test's own, so the arithmetic
 * is checkable by hand.
 */
class HudBarHeightTest {

    private static final HudPosition TOP_LEFT =
            new HudPosition(HudPosition.AnchorEdge.TOP, HudPosition.HorizontalEdge.LEFT, 16, 90);

    private static final HudPosition BOTTOM_LEFT =
            new HudPosition(HudPosition.AnchorEdge.BOTTOM, HudPosition.HorizontalEdge.LEFT, 16, 16);

    /** The document's vertical numbers, stated by the test so the arithmetic below is checkable by hand. */
    private static final int PADDING = 10;
    private static final int MARGIN = 4;
    private static final int LINE = 20;
    private static final int BAR = 10;

    private static final int FILL_ROW = MARGIN + LINE + BAR;
    private static final int ITEM_ROW = MARGIN + LINE;

    private static final HudBarLayout LAYOUT = new HudBarLayout("test", "test:hud", "Hud/Test.ui", "#Test",
            3, 6, 12, 200, 8, 130, PADDING, MARGIN, LINE, BAR, TOP_LEFT);

    private static Row fill(String id) {
        return new Row(id, look(id), null, 1, 1, new HudBarReading(1, 2));
    }

    private static Row item(String id) {
        return new Row(HudBars.itemRowId(id), look(id), id, 3, 1, null);
    }

    private static HudBarLook look(String id) {
        return HudBarLook.resolve(id, null, HudBarDisplay.NONE);
    }

    private static List<Row> rows(int fills, int items) {
        List<Row> rows = new ArrayList<>();
        for (int i = 0; i < fills; i++) {
            rows.add(fill("f" + i));
        }
        for (int i = 0; i < items; i++) {
            rows.add(item("i" + i));
        }
        return rows;
    }

    private static HudBarPlacement at(HudPosition corner, HudBarGap gap, int minHeight) {
        return at(corner, gap, null, minHeight);
    }

    private static HudBarPlacement at(HudPosition corner, HudBarGap gap, HudBarCutout cutout, int minHeight) {
        return new HudBarPlacement(corner, 3, 6, gap, cutout, minHeight, null, null);
    }

    // ==================== the height ====================

    @Test
    void aRowWithAFillIsTallerThanARowAboutAnItem() {
        assertEquals(FILL_ROW, LAYOUT.rowHeightPx(true), "margin, line and the bar block");
        assertEquals(ITEM_ROW, LAYOUT.rowHeightPx(false), "margin and line alone: the bar is hidden");
        assertEquals(PADDING * 2, LAYOUT.panelHeightFor(0), "nothing showing is the padding alone");
        assertEquals(PADDING * 2 + 7, LAYOUT.panelHeightFor(7));
    }

    @Test
    void theHeightIsTheTallestUsedColumnInsideThePadding() {
        // Three fill rows fill the first column, two item rows the second: the first is the taller.
        int height = HudBarHud.panelHeightFor(rows(3, 2), 2, 3, LAYOUT, at(TOP_LEFT, null, 0));

        assertEquals(PADDING * 2 + 3 * FILL_ROW, height);
        assertEquals(PADDING * 2, HudBarHud.panelHeightFor(List.of(), 0, 0, LAYOUT, at(TOP_LEFT, null, 0)),
                "nothing showing");
    }

    @Test
    void aColumnOfBarLessRowsIsShorterThanAColumnOfFills() {
        assertEquals(PADDING * 2 + 3 * ITEM_ROW,
                HudBarHud.panelHeightFor(rows(0, 3), 1, 3, LAYOUT, at(TOP_LEFT, null, 0)));
        assertEquals(PADDING * 2 + 3 * FILL_ROW,
                HudBarHud.panelHeightFor(rows(3, 0), 1, 3, LAYOUT, at(TOP_LEFT, null, 0)));
        assertEquals(PADDING * 2 + 2 * FILL_ROW + ITEM_ROW,
                HudBarHud.panelHeightFor(rows(2, 1), 1, 3, LAYOUT, at(TOP_LEFT, null, 0)),
                "a mixed column is summed row by row, never counted");
    }

    @Test
    void theBandAddsToAColumnThatCarriesOneAndOnlyThen() {
        HudBarGap gap = new HudBarGap(1, 30);

        assertEquals(PADDING * 2 + 3 * FILL_ROW + 30,
                HudBarHud.panelHeightFor(rows(3, 0), 1, 3, LAYOUT, at(TOP_LEFT, gap, 0)),
                "three rows, a band after the first");
        assertEquals(PADDING * 2 + FILL_ROW,
                HudBarHud.panelHeightFor(rows(1, 0), 1, 1, LAYOUT, at(TOP_LEFT, gap, 0)),
                "one row has nothing past the band's row, so no band");
        assertEquals(PADDING * 2 + 3 * FILL_ROW + 30,
                HudBarHud.panelHeightFor(rows(3, 0), 1, 3, LAYOUT, at(BOTTOM_LEFT, gap, 0)),
                "the same height bottom-up: the band moves slot, not size");
    }

    @Test
    void theBandRunsAtTheSameRowAcrossEveryColumnAndAShortColumnMayMissIt() {
        // Eight rows three deep: two full columns and a third holding two. A band after the first
        // row lands in all three; a band after the second lands only in the full ones, and the
        // panel is as tall as the tallest column either way.
        assertEquals(PADDING * 2 + 3 * FILL_ROW + 30,
                HudBarHud.panelHeightFor(rows(8, 0), 3, 3, LAYOUT, at(TOP_LEFT, new HudBarGap(1, 30), 0)));
        assertEquals(PADDING * 2 + 3 * FILL_ROW + 30,
                HudBarHud.panelHeightFor(rows(8, 0), 3, 3, LAYOUT, at(TOP_LEFT, new HudBarGap(2, 30), 0)));
        assertEquals(PADDING * 2 + 3 * FILL_ROW,
                HudBarHud.panelHeightFor(rows(8, 0), 3, 3, LAYOUT, at(TOP_LEFT, new HudBarGap(3, 30), 0)),
                "no column has a fourth row, so nothing carries a band after the third");
    }

    @Test
    void theFloorHoldsAShortPanelUpAndNeverAShortensATallOne() {
        assertEquals(200, HudBarHud.panelHeightFor(rows(1, 0), 1, 1, LAYOUT, at(TOP_LEFT, null, 200)));
        assertEquals(PADDING * 2 + 3 * FILL_ROW,
                HudBarHud.panelHeightFor(rows(3, 0), 1, 3, LAYOUT, at(TOP_LEFT, null, 100)),
                "a busier panel grows past its floor");
        assertEquals(200, HudBarHud.panelHeightFor(List.of(), 0, 0, LAYOUT, at(TOP_LEFT, null, 200)),
                "the floor stands even with nothing showing");
    }

    // ==================== the band's slot ====================

    @Test
    void topDownTheBandSitsOnTheSlotJustPastAfterRow() {
        // Slot r holds ordinal r, and row 1 is ordinal 0 against the top edge. After three rows
        // (slots 0..2) the band is the taller margin of slot 3, the first row below the split.
        HudBarGap gap = new HudBarGap(3, 48);

        assertEquals(3, HudBarHud.gapSlotFor(gap, 0, 6, 6, false));
        assertEquals(3, HudBarHud.gapSlotFor(gap, 0, 4, 6, false), "a short column still reaches past the third row");
        assertEquals(3, HudBarHud.ordinalInColumn(HudBarHud.gapSlotFor(gap, 0, 6, 6, false), 6, false),
                "the slot carrying the band draws the row just past AfterRow");
        assertEquals(-1, HudBarHud.gapSlotFor(gap, 0, 3, 6, false), "three rows: nothing sits past the third");
    }

    @Test
    void bottomUpTheBandSitsOnTheSlotHoldingTheRowJustBelowTheSplit() {
        // Slot r holds ordinal perColumn - 1 - r, so row 1 (ordinal 0) is the lowest slot, against
        // the bottom edge. After three rows the band separates ordinal 2 (row 3, the top of the
        // lower group) from ordinal 3 above it, and the taller margin goes ABOVE row 3: on the
        // slot holding ordinal AfterRow - 1, which is slot perColumn - AfterRow.
        HudBarGap gap = new HudBarGap(3, 48);

        assertEquals(3, HudBarHud.gapSlotFor(gap, 0, 6, 6, true));
        assertEquals(2, HudBarHud.ordinalInColumn(HudBarHud.gapSlotFor(gap, 0, 6, 6, true), 6, true),
                "the slot carrying the band draws row 3, and the band is the space above it");
        assertEquals(3, HudBarHud.gapSlotFor(gap, 0, 4, 6, true), "four rows: row 4 sits above the band");
        assertEquals(-1, HudBarHud.gapSlotFor(gap, 0, 3, 6, true),
                "three rows: a band above the topmost row would be dead space in the frame");
        assertEquals(4, HudBarHud.gapSlotFor(new HudBarGap(2, 48), 0, 6, 6, true));
        assertEquals(1, HudBarHud.gapSlotFor(new HudBarGap(2, 48), 0, 3, 3, true),
                "a three-deep column: rows 1 and 2 in slots 2 and 1, the band above slot 1");
    }

    @Test
    void noBandWhenItIsAbsentOrEitherNumberIsNotPositive() {
        assertEquals(-1, HudBarHud.gapSlotFor(null, 0, 6, 6, false));
        assertEquals(-1, HudBarHud.gapSlotFor(new HudBarGap(0, 48), 0, 6, 6, false), "after no rows is no band");
        assertEquals(-1, HudBarHud.gapSlotFor(new HudBarGap(-2, 48), 0, 6, 6, true));
        assertEquals(-1, HudBarHud.gapSlotFor(new HudBarGap(3, 0), 0, 6, 6, false), "no height is no band");
        assertEquals(-1, HudBarHud.gapSlotFor(new HudBarGap(3, -1), 0, 6, 6, true));
        assertEquals(-1, HudBarHud.gapSlotFor(new HudBarGap(null, 48), 0, 6, 6, false), "half a band");
        assertEquals(-1, HudBarHud.gapSlotFor(new HudBarGap(3, 48), 0, 6, 0, false), "no depth, no slots");
        assertEquals(-1, HudBarHud.gapSlotFor(new HudBarGap(6, 48), 0, 6, 6, false), "after every row is past the last");
        assertEquals(-1, HudBarHud.gapSlotFor(new HudBarGap(9, 48), 0, 6, 6, true), "further still");
    }

    @Test
    void theBandFollowsTheCutAndACutColumnCarriesItOnlyWhenTheSplitFallsBetweenItsOwnRows() {
        // A column cut one cell deep draws rows 2..7 in ordinals 0..5. The band after row 3 falls
        // between its second and third rows: bottom-up the slot drawing row 3 (ordinal 1) is slot
        // 6 - 1 - 1 = 4, and top-down the slot drawing row 4 (ordinal 2) is slot 2.
        HudBarGap gap = new HudBarGap(3, 48);

        assertEquals(4, HudBarHud.gapSlotFor(gap, 1, 6, 6, true));
        assertEquals(1, HudBarHud.ordinalInColumn(HudBarHud.gapSlotFor(gap, 1, 6, 6, true), 6, true),
                "bottom-up the band's slot draws the column's second row, which is row 3 on screen");
        assertEquals(2, HudBarHud.gapSlotFor(gap, 1, 6, 6, false));
        assertEquals(2, HudBarHud.gapSlotFor(gap, 1, 3, 6, false), "rows 2, 3 and 4: row 4 sits past the split");
        assertEquals(-1, HudBarHud.gapSlotFor(gap, 1, 2, 6, false), "rows 2 and 3 alone: nothing past the split");
        // A column cut three deep draws rows 4..9: the split after row 3 sits at or below its first
        // row, so the cut swallows it and the column carries no band of its own.
        assertEquals(-1, HudBarHud.gapSlotFor(gap, 3, 6, 6, true));
        assertEquals(-1, HudBarHud.gapSlotFor(gap, 3, 6, 6, false));
        assertEquals(-1, HudBarHud.gapSlotFor(gap, 5, 4, 6, true), "cut deeper than the split, the same");
    }

    // ==================== the cut ====================

    @Test
    void aCutColumnNeverStandsTallerAndItsSurplusSpillsAbove() {
        // Nine fill rows three deep across three columns, the third cut two cells: drawing its
        // three rows above two empty cells it would reach five rows from the edge while the others
        // reach three. It keeps the one row that fits under their top; the other two go into a
        // fourth row above the block, one to the first column and one to the second, so the frame
        // is four rows tall (one for the spilled row) rather than five (two for a cut column
        // standing alone).
        HudBarPlacement cut = at(BOTTOM_LEFT, null, new HudBarCutout(3, 2), 0);

        assertEquals(PADDING * 2 + 4 * FILL_ROW, HudBarHud.panelHeightFor(rows(9, 0), 3, 3, LAYOUT, cut));
        assertEquals(4 * FILL_ROW, HudBarHud.innerHeightFor(rows(9, 0), 3, 3, LAYOUT, cut));
        assertEquals(4, HudBarHud.shapeOf(rows(9, 0), 0, 3, LAYOUT, cut).drawn(), "the first column took a spilled row");
        assertEquals(4, HudBarHud.shapeOf(rows(9, 0), 1, 3, LAYOUT, cut).drawn(), "and the second the other");
        ColumnShape squeezed = HudBarHud.shapeOf(rows(9, 0), 2, 3, LAYOUT, cut);
        assertEquals(1, squeezed.drawn(), "the cut column keeps the one row that fits under the others' top");
        assertEquals(2 * FILL_ROW, squeezed.reservedPx());
        assertEquals(3 * FILL_ROW, squeezed.extentPx(), "two empty cells and a row: never past the block");
        assertEquals(PADDING * 2 + 3 * FILL_ROW, HudBarHud.panelHeightFor(rows(9, 0), 3, 3, LAYOUT,
                at(BOTTOM_LEFT, null, 0)), "the same rows with no cut");
        assertEquals(PADDING * 2 + 4 * FILL_ROW, HudBarHud.panelHeightFor(rows(9, 0), 3, 3, LAYOUT,
                at(TOP_LEFT, null, new HudBarCutout(3, 2), 0)), "the cut reserves the same space top-down");
    }

    @Test
    void theCutReservesTheBandTooWhenTheSplitFallsInsideIt() {
        // Two columns of three, a band after the first row, the second column cut two cells: the
        // split falls inside its cut, so that column's rows all sit above the band and its reserved
        // space is the two cells plus the band. Under the first column's top it has room for one of
        // its three rows; the other two spill into a fourth row above the block, one to each
        // column. The first column carries the band between its own rows. Both reach the same
        // height, four rows and the band, and the frame holds either.
        HudBarPlacement cut = at(BOTTOM_LEFT, new HudBarGap(1, 30), new HudBarCutout(2, 2), 0);
        ColumnShape plain = HudBarHud.shapeOf(rows(6, 0), 0, 3, LAYOUT, cut);
        ColumnShape swallowed = HudBarHud.shapeOf(rows(6, 0), 1, 3, LAYOUT, cut);

        assertEquals(4, plain.drawn(), "three of its own and one spilled");
        assertEquals(0, plain.reservedPx());
        assertEquals(30, plain.bandPx(), "the first column carries the band");
        assertEquals(4 * FILL_ROW + 30, plain.extentPx());
        assertEquals(2, swallowed.drawn(), "the one that fits under the block's top, and one spilled above it");
        assertEquals(2 * FILL_ROW + 30, swallowed.reservedPx(), "two empty cells and the band under them");
        assertEquals(-1, swallowed.bandSlot(), "no band between its own rows");
        assertEquals(0, swallowed.bandPx());
        assertEquals(4 * FILL_ROW + 30, swallowed.extentPx());
        assertEquals(PADDING * 2 + 4 * FILL_ROW + 30, HudBarHud.panelHeightFor(rows(6, 0), 2, 3, LAYOUT, cut));
    }

    @Test
    void aCutColumnMayStillCarryTheBandWhenTheSplitFallsBetweenItsRows() {
        // The second column cut one cell with a band after the third row: its rows are 2..4 and the
        // split falls between rows 3 and 4, so it carries the band as the first column does, one
        // slot further up bottom-up. Its fourth row has no room under the first column's top and
        // spills into a fifth row there, so every column's slots are mapped five deep.
        HudBarPlacement cut = at(BOTTOM_LEFT, new HudBarGap(3, 30), new HudBarCutout(2, 1), 0);
        ColumnShape plain = HudBarHud.shapeOf(rows(8, 0), 0, 4, LAYOUT, cut);
        ColumnShape shifted = HudBarHud.shapeOf(rows(8, 0), 1, 4, LAYOUT, cut);

        assertEquals(5, plain.drawn(), "its four and the cut column's spilled fourth");
        assertEquals(3, shifted.drawn());
        assertEquals(2, plain.bandSlot(), "five deep, the band above the slot drawing row 3: slot 5 - 3");
        assertEquals(3, shifted.bandSlot(), "cut one, the same row sits one slot higher: 5 - 3 + 1");
        assertEquals(30, shifted.bandPx());
        assertEquals(FILL_ROW, shifted.reservedPx(), "the cut cell alone: the band is between its rows, not under them");
        assertEquals(FILL_ROW + 30 + 3 * FILL_ROW, shifted.extentPx());
        assertEquals(5 * FILL_ROW + 30, plain.extentPx());
    }

    @Test
    void aCutNamingAColumnTheRowsNeverOpenCutsNothing() {
        HudBarPlacement cut = at(BOTTOM_LEFT, null, new HudBarCutout(3, 2), 0);

        assertEquals(PADDING * 2 + 2 * FILL_ROW, HudBarHud.panelHeightFor(rows(4, 0), 2, 2, LAYOUT, cut),
                "four rows two deep open two columns, and the cut names the third");
        assertEquals(0, HudBarHud.shapeOf(rows(4, 0), 0, 2, LAYOUT, cut).reservedPx());
        assertEquals(0, HudBarHud.shapeOf(rows(4, 0), 1, 2, LAYOUT, cut).reservedPx());
        assertEquals(0, HudBarHud.shapeOf(rows(4, 0), 1, 2, LAYOUT, cut).leadingPx(2 * FILL_ROW, true),
                "both columns reach the full height, so neither is pushed");
    }

    @Test
    void aCutDeeperThanTheBlockLeavesThatColumnNothingToDrawAndItsRowsSpillBeside() {
        // Four rows two deep on a six-deep document, the second column cut nine cells: it has no
        // cell at all, so it draws nothing and has no extent. Its two rows are not lost: they spill
        // into the first column as rows three and four above the block, and the frame is sized to
        // that. A cut of five leaves it one cell, but a cell five rows up beside a two-row column is
        // past the block, so it draws nothing there either and its rows spill the same way.
        HudBarPlacement gone = at(BOTTOM_LEFT, null, new HudBarCutout(2, 9), 0);
        assertSame(ColumnShape.NONE, HudBarHud.shapeOf(rows(4, 0), 1, 2, LAYOUT, gone));
        assertEquals(4, HudBarHud.shapeOf(rows(4, 0), 0, 2, LAYOUT, gone).drawn(), "the first column holds all four");
        assertEquals(PADDING * 2 + 4 * FILL_ROW, HudBarHud.panelHeightFor(rows(4, 0), 2, 2, LAYOUT, gone));
        assertEquals(0, ColumnShape.NONE.leadingPx(2 * FILL_ROW, true), "nothing to push");

        HudBarPlacement oneCell = at(BOTTOM_LEFT, null, new HudBarCutout(2, 5), 0);
        assertSame(ColumnShape.NONE, HudBarHud.shapeOf(rows(4, 0), 1, 2, LAYOUT, oneCell),
                "its one cell sits five rows up, past a two-row block");
        assertEquals(4, HudBarHud.shapeOf(rows(4, 0), 0, 2, LAYOUT, oneCell).drawn());
        assertEquals(PADDING * 2 + 4 * FILL_ROW, HudBarHud.panelHeightFor(rows(4, 0), 2, 2, LAYOUT, oneCell));
    }

    @Test
    void noCutWhenItIsAbsentOrEitherNumberIsNotPositive() {
        assertFalse(new HudBarCutout(null, 3).applies(), "half a cut");
        assertFalse(new HudBarCutout(3, null).applies());
        assertFalse(new HudBarCutout(0, 3).applies(), "no column");
        assertFalse(new HudBarCutout(-1, 3).applies());
        assertFalse(new HudBarCutout(3, 0).applies(), "no rows");
        assertFalse(new HudBarCutout(3, -2).applies());
        assertTrue(new HudBarCutout(3, 3).applies());
        assertEquals(0, new HudBarCutout(3, 0).rowsAt(2), "a cut that does not apply cuts no column");
        assertEquals(3, new HudBarCutout(3, 3).rowsAt(2), "Column 3 is the column at ordinal 2");
        assertEquals(0, new HudBarCutout(3, 3).rowsAt(0));
        assertEquals(0, new HudBarCutout(3, 3).rowsAt(1));
        assertEquals(2, new HudBarCutout(1, 2).rowsAt(0), "Column 1 is the panel's first, at ordinal 0");

        HudBarPlacement off = at(BOTTOM_LEFT, null, new HudBarCutout(3, 0), 0);
        assertEquals(PADDING * 2 + 3 * FILL_ROW, HudBarHud.panelHeightFor(rows(9, 0), 3, 3, LAYOUT, off));
        assertEquals(0, HudBarHud.shapeOf(rows(9, 0), 2, 3, LAYOUT, off).reservedPx());
    }

    // ==================== where each column sits ====================

    @Test
    void aBottomPinnedShortColumnBottomAligns() {
        // Five rows three deep: a full first column and a second holding two. Bottom-up the second
        // stacks from the panel's top and falls one row short of its height, so its first visible
        // slot is pushed down by that row and its rows sit against the pinned edge.
        HudBarPlacement spot = at(BOTTOM_LEFT, null, 0);
        int inner = HudBarHud.innerHeightFor(rows(5, 0), 2, 3, LAYOUT, spot);

        assertEquals(3 * FILL_ROW, inner);
        assertEquals(0, HudBarHud.shapeOf(rows(5, 0), 0, 3, LAYOUT, spot).leadingPx(inner, true),
                "the full column reaches the top");
        assertEquals(FILL_ROW, HudBarHud.shapeOf(rows(5, 0), 1, 3, LAYOUT, spot).leadingPx(inner, true),
                "the short column is pushed down by the row it lacks");
        assertEquals(0, HudBarHud.shapeOf(rows(5, 0), 1, 3, LAYOUT, at(TOP_LEFT, null, 0)).leadingPx(inner, false),
                "top-down a short column simply ends early");

        // Slot by slot: the short column's top slot draws nothing (ordinal 2 of two rows), its
        // middle slot is the first visible one and carries the push, its bottom slot the plain margin.
        ColumnShape shortColumn = HudBarHud.shapeOf(rows(5, 0), 1, 3, LAYOUT, spot);
        assertEquals(2, HudBarHud.ordinalInColumn(0, 3, true), "the top slot would draw a third row");
        assertTrue(HudBarHud.ordinalInColumn(0, 3, true) >= shortColumn.drawn(), "which it does not have");
        assertEquals(FILL_ROW, shortColumn.pushAt(1, HudBarHud.ordinalInColumn(1, 3, true), inner, true));
        assertEquals(0, shortColumn.pushAt(2, HudBarHud.ordinalInColumn(2, 3, true), inner, true));
    }

    @Test
    void theLeadingPushAndTheBandPushAddOnASlotThatCarriesBoth() {
        // Whatever the geometry, a slot that is both a column's first visible slot and the slot
        // below the split carries the two pushes summed, so neither can silently replace the other:
        // a top-down column reserving two cells whose band slot is its first slot.
        ColumnShape shape = new ColumnShape(4, 2 * FILL_ROW, 0, 30, 4 * FILL_ROW);
        int inner = shape.extentPx();

        assertEquals(2 * FILL_ROW, shape.leadingPx(inner, false), "top-down the push is the reserved space");
        assertEquals(2 * FILL_ROW + 30, shape.pushAt(0, 0, inner, false), "slot 0 draws ordinal 0 and holds the band");
        assertEquals(30, shape.pushAt(0, 1, inner, false), "the band alone on the same slot drawing another ordinal");
        assertEquals(0, shape.pushAt(1, 1, inner, false), "a plain slot");
        assertEquals(0, shape.pushAt(1, 3, inner, true), "bottom-up the same column reaches the full height: no push");
    }

    @Test
    void aCutColumnBottomPinnedStandsOnTheEdgeUnderTheSpilledRow() {
        // Nine rows three deep across three columns, the third cut two cells: the two plain
        // columns each take one of the cut column's surplus rows as a fourth and reach the panel's
        // top on their own; the cut column, its one row above two empty cells, is one row short of
        // the panel and is pushed down by that row, so it stands on the pinned edge beneath the
        // spilled row rather than hanging from the panel's top.
        HudBarPlacement spot = at(BOTTOM_LEFT, null, new HudBarCutout(3, 2), 0);
        int inner = HudBarHud.innerHeightFor(rows(9, 0), 3, 3, LAYOUT, spot);

        assertEquals(4 * FILL_ROW, inner);
        assertEquals(0, HudBarHud.shapeOf(rows(9, 0), 0, 3, LAYOUT, spot).leadingPx(inner, true));
        assertEquals(0, HudBarHud.shapeOf(rows(9, 0), 1, 3, LAYOUT, spot).leadingPx(inner, true));
        assertEquals(FILL_ROW, HudBarHud.shapeOf(rows(9, 0), 2, 3, LAYOUT, spot).leadingPx(inner, true),
                "the cut column is pushed down by the one row it falls short");
    }

    @Test
    void aTopPinnedColumnKeepsItsCutAtTheTop() {
        // The same nine rows at a top spot: the plain columns start at the top untouched, and the
        // cut column is pushed down by exactly its two empty cells so its row starts past them.
        HudBarPlacement spot = at(TOP_LEFT, null, new HudBarCutout(3, 2), 0);
        int inner = HudBarHud.innerHeightFor(rows(9, 0), 3, 3, LAYOUT, spot);

        assertEquals(4 * FILL_ROW, inner);
        assertEquals(0, HudBarHud.shapeOf(rows(9, 0), 0, 3, LAYOUT, spot).leadingPx(inner, false));
        assertEquals(0, HudBarHud.shapeOf(rows(9, 0), 1, 3, LAYOUT, spot).leadingPx(inner, false));
        assertEquals(2 * FILL_ROW, HudBarHud.shapeOf(rows(9, 0), 2, 3, LAYOUT, spot).leadingPx(inner, false));

        HudBarPlacement banded = at(TOP_LEFT, new HudBarGap(2, 30), new HudBarCutout(1, 3), 0);
        assertEquals(3 * FILL_ROW + 30, HudBarHud.shapeOf(rows(4, 0), 0, 4, LAYOUT, banded).leadingPx(
                HudBarHud.innerHeightFor(rows(4, 0), 1, 4, LAYOUT, banded), false),
                "a split inside the cut is pushed past with it");
    }

    /** The shipped bottom-left shape on a nine-deep, three-column document: the picture the two tests below draw. */
    private static final HudBarLayout DEEP = new HudBarLayout("test", "test:hud", "Hud/Test.ui", "#Test",
            3, 9, 12, 200, 8, 130, PADDING, MARGIN, LINE, BAR, TOP_LEFT);

    private static final HudBarPlacement SHIPPED_SPOT = new HudBarPlacement(BOTTOM_LEFT, 3, 1, new HudBarGap(3, 66),
            new HudBarCutout(3, 3), 0, null, "BottomLeft");

    /** Which lattice row (1 against the pinned edge) each row index lands on, read off the spread and the cut. */
    private static Map<Integer, Integer> latticeRows(HudBarSpread spread, HudBarCutout cutout) {
        Map<Integer, Integer> at = new TreeMap<>();
        for (int column = 0; column < spread.used(); column++) {
            int[] mine = spread.rowsOf(column);
            for (int i = 0; i < mine.length; i++) {
                at.put(mine[i], cutout.rowsAt(column) + 1 + i);
            }
        }
        return at;
    }

    @Test
    void eighteenRowsThreeAcrossNineDeepWithTheThirdColumnCutThreeSpillExactlyOneRow() {
        // A bottom-pinned three-column spot, one row per column before spreading, a band after
        // the third row and the third column cut three cells, with eighteen rows moving:
        //
        //   row7  [C1] [C2] [C3]   <- the cut column's surplus, one to each column
        //   row6  [C1] [C2] [C3]
        //   row5  [C1] [C2] [C3]
        //   row4  [C1] [C2] [C3]
        //   ------ band ----------
        //   row3  [C1] [C2]  --
        //   row2  [C1] [C2]  --   cut
        //   row1  [C1] [C2]  --
        //   ====== hotbar ========
        //
        // The even split gives every column six rows. The cut column has room for three under the
        // others' top (rows 4 to 6); its other three go into row 7, one per column from the first
        // outward, which is exactly one spilled row. Every column then reaches seven rows from the
        // edge, the plain ones with the band between their third and fourth rows and the cut one
        // with the band inside its cut, so nothing is pushed.
        List<Row> rows = rows(18, 0);

        int allowed = HudBarHud.columnsFor(rows.size(), DEEP, SHIPPED_SPOT);
        int perColumn = HudBarHud.rowsPerColumnFor(rows.size(), allowed, DEEP);
        assertEquals(3, allowed);
        assertEquals(6, perColumn, "eighteen rows split six deep");
        HudBarSpread spread = HudBarHud.spreadOf(rows, perColumn, DEEP, SHIPPED_SPOT);
        assertEquals(3, spread.used());
        assertEquals(7, spread.depth(), "six and the spilled row");
        assertArrayEquals(new int[] {0, 1, 2, 3, 4, 5, 15}, spread.rowsOf(0));
        assertArrayEquals(new int[] {6, 7, 8, 9, 10, 11, 16}, spread.rowsOf(1));
        assertArrayEquals(new int[] {12, 13, 14, 17}, spread.rowsOf(2));

        ColumnShape c1 = HudBarHud.shapeOf(rows, 0, perColumn, DEEP, SHIPPED_SPOT);
        ColumnShape c2 = HudBarHud.shapeOf(rows, 1, perColumn, DEEP, SHIPPED_SPOT);
        ColumnShape c3 = HudBarHud.shapeOf(rows, 2, perColumn, DEEP, SHIPPED_SPOT);
        assertEquals(7 * FILL_ROW + 66, c1.extentPx());
        assertEquals(c1, c2);
        assertEquals(4, c1.bandSlot(), "seven deep, the band above the slot drawing row 3: slot 7 - 3");
        assertEquals(66, c1.bandPx());
        assertEquals(4, c3.drawn(), "three under the others' top and one spilled");
        assertEquals(3 * FILL_ROW + 66, c3.reservedPx(), "three empty cells and the band under them");
        assertEquals(-1, c3.bandSlot(), "all its rows are above the split");
        assertEquals(7 * FILL_ROW + 66, c3.extentPx(), "as tall as the plain columns, never taller");

        int inner = HudBarHud.innerHeightFor(rows, spread.used(), perColumn, DEEP, SHIPPED_SPOT);
        assertEquals(7 * FILL_ROW + 66, inner);
        assertEquals(PADDING * 2 + 7 * FILL_ROW + 66, HudBarHud.panelHeightFor(rows, spread.used(), perColumn, DEEP, SHIPPED_SPOT));
        assertEquals(0, c1.leadingPx(inner, true), "every column reaches the panel's top");
        assertEquals(0, c2.leadingPx(inner, true));
        assertEquals(0, c3.leadingPx(inner, true));

        // Slot by slot, top to bottom, what each margin is pushed by over the document's own, the
        // slots mapped seven deep: the plain columns carry the band on the slot drawing row 3 and
        // nothing elsewhere; the cut column draws in slots 3 to 6 alone (its ordinals 3 down to 0),
        // hides slots 0 to 2, and pushes nothing, its empty cells being the space below its lowest
        // slot. Slots 7 and 8 draw nothing in any column.
        int depth = spread.depth();
        int[] plain = {0, 0, 0, 0, 66, 0, 0};
        for (int slot = 0; slot < depth; slot++) {
            int ordinal = HudBarHud.ordinalInColumn(slot, depth, true);
            assertEquals(plain[slot], c1.pushAt(slot, ordinal, inner, true), "column 1, slot " + slot);
            assertEquals(plain[slot], c2.pushAt(slot, ordinal, inner, true), "column 2, slot " + slot);
            assertEquals(slot >= 3, ordinal < c3.drawn(), "the cut column draws in slots 3 to 6: slot " + slot);
            if (ordinal < c3.drawn()) {
                assertEquals(0, c3.pushAt(slot, ordinal, inner, true), "column 3, slot " + slot);
            }
        }
        assertEquals(2, HudBarHud.ordinalInColumn(4, depth, true), "slot 4 draws row 3, the top of the lower group");
        assertEquals(6, HudBarHud.ordinalInColumn(0, depth, true), "slot 0 draws row 7, the spilled one");
    }

    @Test
    void twentyRowsThreeAcrossNineDeepWithTheThirdColumnCutThreeSpillTwoIntoARowAbove() {
        // The same spot with twenty rows moving, which the even split deals 7 / 7 / 6:
        //
        //   row8  [C1] [C2]         <- the two rows the cut column had no room for, first column outward
        //   row7  [C1] [C2] [C3]
        //   row6  [C1] [C2] [C3]
        //   row5  [C1] [C2] [C3]
        //   row4  [C1] [C2] [C3]
        //   ------ band ----------
        //   row3  [C1] [C2]  --
        //   row2  [C1] [C2]  --   cut
        //   row1  [C1] [C2]  --
        //   ====== hotbar ========
        //
        // Rows 0 to 6 climb the first column and 7 to 13 the second, to row 7. The cut column's
        // six (14 to 19) would reach row 9 above its three empty cells; it keeps the four that fit
        // under row 7 (14 to 17, in rows 4 to 7), and 18 and 19 go into row 8, in the first and
        // the second column. The panel is eight rows and the band tall, one row less than the cut
        // column standing alone would have made it, with no empty frame over the plain columns,
        // and the cut column is pushed down by the one row it falls short.
        List<Row> rows = rows(20, 0);

        int allowed = HudBarHud.columnsFor(rows.size(), DEEP, SHIPPED_SPOT);
        int perColumn = HudBarHud.rowsPerColumnFor(rows.size(), allowed, DEEP);
        assertEquals(3, allowed);
        assertEquals(7, perColumn, "twenty rows split seven deep");
        HudBarSpread spread = HudBarHud.spreadOf(rows, perColumn, DEEP, SHIPPED_SPOT);
        assertEquals(3, spread.used());
        assertEquals(8, spread.depth(), "seven and the spilled row");
        assertArrayEquals(new int[] {0, 1, 2, 3, 4, 5, 6, 18}, spread.rowsOf(0));
        assertArrayEquals(new int[] {7, 8, 9, 10, 11, 12, 13, 19}, spread.rowsOf(1));
        assertArrayEquals(new int[] {14, 15, 16, 17}, spread.rowsOf(2));

        Map<Integer, Integer> expected = new TreeMap<>();
        for (int i = 0; i <= 6; i++) {
            expected.put(i, i + 1);
        }
        for (int i = 7; i <= 13; i++) {
            expected.put(i, i - 6);
        }
        for (int i = 14; i <= 17; i++) {
            expected.put(i, i - 10);
        }
        expected.put(18, 8);
        expected.put(19, 8);
        assertEquals(expected, latticeRows(spread, SHIPPED_SPOT.cutout()),
                "which lattice row each index lands on, 1 being the row against the hotbar");

        ColumnShape c1 = HudBarHud.shapeOf(rows, 0, perColumn, DEEP, SHIPPED_SPOT);
        ColumnShape c2 = HudBarHud.shapeOf(rows, 1, perColumn, DEEP, SHIPPED_SPOT);
        ColumnShape c3 = HudBarHud.shapeOf(rows, 2, perColumn, DEEP, SHIPPED_SPOT);
        assertEquals(8 * FILL_ROW + 66, c1.extentPx());
        assertEquals(c1, c2);
        assertEquals(5, c1.bandSlot(), "eight deep, the band above the slot drawing row 3: slot 8 - 3");
        assertEquals(4, c3.drawn(), "the four that fit under row 7");
        assertEquals(3 * FILL_ROW + 66, c3.reservedPx());
        assertEquals(-1, c3.bandSlot());
        assertEquals(7 * FILL_ROW + 66, c3.extentPx(), "one row short of the spilled row");

        int inner = HudBarHud.innerHeightFor(rows, spread.used(), perColumn, DEEP, SHIPPED_SPOT);
        assertEquals(8 * FILL_ROW + 66, inner);
        assertEquals(PADDING * 2 + 8 * FILL_ROW + 66, HudBarHud.panelHeightFor(rows, spread.used(), perColumn, DEEP, SHIPPED_SPOT),
                "eight rows and the band: the cut column's six above its cut would have made it nine");
        assertEquals(0, c1.leadingPx(inner, true));
        assertEquals(0, c2.leadingPx(inner, true));
        assertEquals(FILL_ROW, c3.leadingPx(inner, true), "the cut column stands on the edge under row 8");

        // Slot by slot, the slots mapped eight deep: the plain columns carry the band on the slot
        // drawing row 3; the cut column hides slots 0 to 3, its first visible slot (4, drawing its
        // topmost row, row 7) carries its leading push, and the rest carry nothing.
        int depth = spread.depth();
        int[] plain = {0, 0, 0, 0, 0, 66, 0, 0};
        int[] cut = {-1, -1, -1, -1, FILL_ROW, 0, 0, 0};
        for (int slot = 0; slot < depth; slot++) {
            int ordinal = HudBarHud.ordinalInColumn(slot, depth, true);
            assertEquals(plain[slot], c1.pushAt(slot, ordinal, inner, true), "column 1, slot " + slot);
            assertEquals(plain[slot], c2.pushAt(slot, ordinal, inner, true), "column 2, slot " + slot);
            assertEquals(cut[slot] >= 0, ordinal < c3.drawn(), "the cut column draws in slots 4 to 7: slot " + slot);
            if (cut[slot] >= 0) {
                assertEquals(cut[slot], c3.pushAt(slot, ordinal, inner, true), "column 3, slot " + slot);
            }
        }
        assertEquals(2, HudBarHud.ordinalInColumn(5, depth, true), "slot 5 draws row 3, the top of the lower group");
        assertEquals(7, HudBarHud.ordinalInColumn(0, depth, true), "slot 0 draws row 8, the spilled one");
    }
}
