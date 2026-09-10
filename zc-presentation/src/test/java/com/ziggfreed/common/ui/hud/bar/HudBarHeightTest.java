package com.ziggfreed.common.ui.hud.bar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

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
 * reserves them below its first row and stands taller than the rest. The band is one slot's taller
 * margin, so which slot carries it is worked out here in both directions a column can fill, with
 * and without a cut; and every column is placed by a push on its first visible slot, so a
 * bottom-pinned column shorter than the tallest bottom-aligns and a top-pinned one keeps its cut at
 * the top. Every number below is the test's own, so the arithmetic is checkable by hand.
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
    void aCutColumnIsTheTallestAndTheFrameGrowsToHoldIt() {
        // Nine fill rows three deep across three columns, the third cut two cells: it draws its
        // three rows above two empty cells, so it reaches five rows from the edge while the others
        // reach three, and the frame is sized to it.
        HudBarPlacement cut = at(BOTTOM_LEFT, null, new HudBarCutout(3, 2), 0);

        assertEquals(PADDING * 2 + 5 * FILL_ROW, HudBarHud.panelHeightFor(rows(9, 0), 3, 3, LAYOUT, cut));
        assertEquals(5 * FILL_ROW, HudBarHud.innerHeightFor(rows(9, 0), 3, 3, LAYOUT, cut));
        assertEquals(PADDING * 2 + 3 * FILL_ROW, HudBarHud.panelHeightFor(rows(9, 0), 3, 3, LAYOUT,
                at(BOTTOM_LEFT, null, 0)), "the same rows with no cut");
        assertEquals(PADDING * 2 + 5 * FILL_ROW, HudBarHud.panelHeightFor(rows(9, 0), 3, 3, LAYOUT,
                at(TOP_LEFT, null, new HudBarCutout(3, 2), 0)), "the cut reserves the same space top-down");
    }

    @Test
    void theCutReservesTheBandTooWhenTheSplitFallsInsideIt() {
        // Two columns of three, a band after the first row, the second column cut two cells: the
        // split falls inside its cut, so that column's rows all sit above the band and its reserved
        // space is the two cells plus the band. The first column carries the band between its own
        // rows. Both reach the same height, and the frame holds either.
        HudBarPlacement cut = at(BOTTOM_LEFT, new HudBarGap(1, 30), new HudBarCutout(2, 2), 0);
        ColumnShape plain = HudBarHud.shapeOf(rows(6, 0), 0, 3, LAYOUT, cut);
        ColumnShape swallowed = HudBarHud.shapeOf(rows(6, 0), 1, 3, LAYOUT, cut);

        assertEquals(0, plain.reservedPx());
        assertEquals(30, plain.bandPx(), "the first column carries the band");
        assertEquals(3 * FILL_ROW + 30, plain.extentPx());
        assertEquals(2 * FILL_ROW + 30, swallowed.reservedPx(), "two empty cells and the band under them");
        assertEquals(-1, swallowed.bandSlot(), "no band between its own rows");
        assertEquals(0, swallowed.bandPx());
        assertEquals(5 * FILL_ROW + 30, swallowed.extentPx());
        assertEquals(PADDING * 2 + 5 * FILL_ROW + 30, HudBarHud.panelHeightFor(rows(6, 0), 2, 3, LAYOUT, cut));
    }

    @Test
    void aCutColumnMayStillCarryTheBandWhenTheSplitFallsBetweenItsRows() {
        // The second column cut one cell with a band after the third row: its rows are 2..4 and the
        // split falls between rows 3 and 4, so it carries the band as the first column does, one
        // slot further up bottom-up.
        HudBarPlacement cut = at(BOTTOM_LEFT, new HudBarGap(3, 30), new HudBarCutout(2, 1), 0);
        ColumnShape plain = HudBarHud.shapeOf(rows(8, 0), 0, 4, LAYOUT, cut);
        ColumnShape shifted = HudBarHud.shapeOf(rows(8, 0), 1, 4, LAYOUT, cut);

        assertEquals(1, plain.bandSlot(), "four deep, the band above the slot drawing row 3: slot 4 - 3");
        assertEquals(2, shifted.bandSlot(), "cut one, the same row sits one slot higher: 4 - 3 + 1");
        assertEquals(30, shifted.bandPx());
        assertEquals(FILL_ROW, shifted.reservedPx(), "the cut cell alone: the band is between its rows, not under them");
        assertEquals(FILL_ROW + 30 + 4 * FILL_ROW, shifted.extentPx());
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
    void aCutDeeperThanTheDocumentLeavesThatColumnNothingToDraw() {
        // The document is six deep. A cut of six or more leaves the second column no cell at all:
        // it draws nothing, has no extent, and the frame is sized to the first column alone. A cut
        // of five leaves it one cell, so of its two rows it draws one, above five empty cells.
        HudBarPlacement gone = at(BOTTOM_LEFT, null, new HudBarCutout(2, 9), 0);
        assertSame(ColumnShape.NONE, HudBarHud.shapeOf(rows(4, 0), 1, 2, LAYOUT, gone));
        assertEquals(PADDING * 2 + 2 * FILL_ROW, HudBarHud.panelHeightFor(rows(4, 0), 2, 2, LAYOUT, gone));
        assertEquals(0, ColumnShape.NONE.leadingPx(2 * FILL_ROW, true), "nothing to push");

        HudBarPlacement oneCell = at(BOTTOM_LEFT, null, new HudBarCutout(2, 5), 0);
        ColumnShape squeezed = HudBarHud.shapeOf(rows(4, 0), 1, 2, LAYOUT, oneCell);
        assertEquals(1, squeezed.drawn(), "two rows given, one cell left: the other is not drawn");
        assertEquals(5 * FILL_ROW, squeezed.reservedPx());
        assertEquals(6 * FILL_ROW, squeezed.extentPx());
        assertEquals(PADDING * 2 + 6 * FILL_ROW, HudBarHud.panelHeightFor(rows(4, 0), 2, 2, LAYOUT, oneCell));
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
    void aCutColumnBottomPinnedRidesUpAndTheOthersArePushedDownToMeetIt() {
        // Six rows two deep across three columns, the third cut two cells: its rows start two
        // cells up and it reaches four rows from the edge, so the panel is four rows tall and the
        // two plain columns are pushed down by two rows to bottom-align inside it.
        HudBarPlacement spot = at(BOTTOM_LEFT, null, new HudBarCutout(3, 2), 0);
        int inner = HudBarHud.innerHeightFor(rows(6, 0), 3, 2, LAYOUT, spot);

        assertEquals(4 * FILL_ROW, inner);
        assertEquals(2 * FILL_ROW, HudBarHud.shapeOf(rows(6, 0), 0, 2, LAYOUT, spot).leadingPx(inner, true));
        assertEquals(2 * FILL_ROW, HudBarHud.shapeOf(rows(6, 0), 1, 2, LAYOUT, spot).leadingPx(inner, true));
        assertEquals(0, HudBarHud.shapeOf(rows(6, 0), 2, 2, LAYOUT, spot).leadingPx(inner, true),
                "the cut column already starts above its cut: no push");
    }

    @Test
    void aTopPinnedColumnKeepsItsCutAtTheTop() {
        // The same six rows at a top spot: the plain columns start at the top untouched, and the
        // cut column is pushed down by exactly its two empty cells so its rows start past them.
        HudBarPlacement spot = at(TOP_LEFT, null, new HudBarCutout(3, 2), 0);
        int inner = HudBarHud.innerHeightFor(rows(6, 0), 3, 2, LAYOUT, spot);

        assertEquals(4 * FILL_ROW, inner);
        assertEquals(0, HudBarHud.shapeOf(rows(6, 0), 0, 2, LAYOUT, spot).leadingPx(inner, false));
        assertEquals(0, HudBarHud.shapeOf(rows(6, 0), 1, 2, LAYOUT, spot).leadingPx(inner, false));
        assertEquals(2 * FILL_ROW, HudBarHud.shapeOf(rows(6, 0), 2, 2, LAYOUT, spot).leadingPx(inner, false));

        HudBarPlacement banded = at(TOP_LEFT, new HudBarGap(2, 30), new HudBarCutout(1, 3), 0);
        assertEquals(3 * FILL_ROW + 30, HudBarHud.shapeOf(rows(4, 0), 0, 4, LAYOUT, banded).leadingPx(
                HudBarHud.innerHeightFor(rows(4, 0), 1, 4, LAYOUT, banded), false),
                "a split inside the cut is pushed past with it");
    }

    @Test
    void eighteenRowsThreeAcrossSixDeepWithTheThirdColumnCutThree() {
        // A bottom-pinned three-column spot, one row per column before spreading, a band after
        // the third row and the third column cut three cells, with eighteen rows moving:
        //
        //   row9              [C3]
        //   row8              [C3]
        //   row7              [C3]
        //   row6  [C1] [C2]   [C3]
        //   row5  [C1] [C2]   [C3]
        //   row4  [C1] [C2]   [C3]
        //   ------ band ----------
        //   row3  [C1] [C2]    --
        //   row2  [C1] [C2]    --   cut
        //   row1  [C1] [C2]    --
        //   ====== hotbar ========
        //
        // The even split gives every column six rows; the cut column's six sit above its three
        // empty cells, so it reaches nine rows from the edge (and carries no band, the split being
        // inside its cut), the two plain columns reach six rows plus the band, and they are pushed
        // down by the difference so all three stand on the pinned edge.
        HudBarLayout deep = new HudBarLayout("test", "test:hud", "Hud/Test.ui", "#Test",
                3, 9, 12, 200, 8, 130, PADDING, MARGIN, LINE, BAR, TOP_LEFT);
        HudBarPlacement spot = new HudBarPlacement(BOTTOM_LEFT, 3, 1, new HudBarGap(3, 66), new HudBarCutout(3, 3),
                0, null, "BottomLeft");
        List<Row> rows = rows(18, 0);

        int allowed = HudBarHud.columnsFor(rows.size(), deep, spot);
        int perColumn = HudBarHud.rowsPerColumnFor(rows.size(), allowed, deep);
        int used = HudBarHud.usedColumnsFor(rows.size(), perColumn);
        assertEquals(3, allowed);
        assertEquals(6, perColumn, "eighteen rows split six deep");
        assertEquals(3, used);

        ColumnShape c1 = HudBarHud.shapeOf(rows, 0, perColumn, deep, spot);
        ColumnShape c2 = HudBarHud.shapeOf(rows, 1, perColumn, deep, spot);
        ColumnShape c3 = HudBarHud.shapeOf(rows, 2, perColumn, deep, spot);
        assertEquals(6 * FILL_ROW + 66, c1.extentPx());
        assertEquals(c1, c2);
        assertEquals(3, c1.bandSlot(), "the band above the slot drawing row 3");
        assertEquals(66, c1.bandPx());
        assertEquals(6, c3.drawn(), "the cut column holds exactly its six: nine cells less three");
        assertEquals(3 * FILL_ROW + 66, c3.reservedPx(), "three empty cells and the band under them");
        assertEquals(-1, c3.bandSlot(), "all its rows are above the split");
        assertEquals(9 * FILL_ROW + 66, c3.extentPx());

        int inner = HudBarHud.innerHeightFor(rows, used, perColumn, deep, spot);
        assertEquals(9 * FILL_ROW + 66, inner);
        assertEquals(PADDING * 2 + 9 * FILL_ROW + 66, HudBarHud.panelHeightFor(rows, used, perColumn, deep, spot));
        assertEquals(3 * FILL_ROW, c1.leadingPx(inner, true), "the plain columns are pushed down by three rows");
        assertEquals(3 * FILL_ROW, c2.leadingPx(inner, true));
        assertEquals(0, c3.leadingPx(inner, true), "the cut column stands at the panel's top");

        // Slot by slot, top to bottom, what each margin is pushed by over the document's own. The
        // plain columns: three rows on the topmost slot (drawing row 6), the band on the slot
        // drawing row 3, nothing elsewhere; the cut column: nothing anywhere, its empty cells being
        // the space left below its lowest slot. Slots 6 to 8 draw nothing in any column.
        int[] plain = {3 * FILL_ROW, 0, 0, 66, 0, 0};
        int[] cut = {0, 0, 0, 0, 0, 0};
        for (int slot = 0; slot < perColumn; slot++) {
            int ordinal = HudBarHud.ordinalInColumn(slot, perColumn, true);
            assertEquals(plain[slot], c1.pushAt(slot, ordinal, inner, true), "column 1, slot " + slot);
            assertEquals(plain[slot], c2.pushAt(slot, ordinal, inner, true), "column 2, slot " + slot);
            assertEquals(cut[slot], c3.pushAt(slot, ordinal, inner, true), "column 3, slot " + slot);
            assertTrue(ordinal < c3.drawn(), "every one of the six slots draws in the cut column");
        }
        assertEquals(2, HudBarHud.ordinalInColumn(3, perColumn, true), "slot 3 draws row 3, the top of the lower group");
        assertEquals(5, HudBarHud.ordinalInColumn(0, perColumn, true), "slot 0 draws row 6, the column's topmost");
    }
}
