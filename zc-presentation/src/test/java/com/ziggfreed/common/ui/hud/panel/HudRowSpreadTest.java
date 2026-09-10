package com.ziggfreed.common.ui.hud.panel;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.TreeSet;

import org.junit.jupiter.api.Test;

/**
 * Which row each column draws. With no cut anywhere the answer is the even split, column by
 * column, and nothing here changes it. With a cut, no column stands taller than the columns without
 * one: the cut column keeps the rows that fit under their top, and the rest go into a row of their
 * own above the block, filled from the first column outward, then another above that, and a row the
 * document has no cell for is undrawn. Every number is the test's own; the pixels those rows add up
 * to are {@code HudPanelHeightTest}'s business.
 */
class HudRowSpreadTest {

    private static int[] range(int from, int toInclusive) {
        int[] out = new int[toInclusive - from + 1];
        for (int i = 0; i < out.length; i++) {
            out[i] = from + i;
        }
        return out;
    }

    @Test
    void withNoCutEveryColumnDrawsItsShareAndNothingSpills() {
        HudRowSpread spread = HudRowSpread.of(20, 7, 9, null);

        assertEquals(3, spread.used());
        assertEquals(7, spread.depth());
        assertArrayEquals(range(0, 6), spread.rowsOf(0));
        assertArrayEquals(range(7, 13), spread.rowsOf(1));
        assertArrayEquals(range(14, 19), spread.rowsOf(2), "the last column is the short one");
        assertEquals(0, spread.rowsOf(3).length, "past the last column");
        assertEquals(0, spread.rowsOf(-1).length);

        HudRowSpread off = HudRowSpread.of(20, 7, 9, new HudSpotCutout(3, 0));
        assertArrayEquals(spread.rowsOf(2), off.rowsOf(2), "a cut that does not apply changes nothing");
        assertArrayEquals(new int[] {0, 1, 2}, HudRowSpread.of(5, 3, 6, null).rowsOf(0));
        assertArrayEquals(new int[] {3, 4}, HudRowSpread.of(5, 3, 6, null).rowsOf(1));
    }

    @Test
    void nothingShowingIsNoColumnAtAll() {
        assertSame(HudRowSpread.EMPTY, HudRowSpread.of(0, 3, 6, null));
        assertSame(HudRowSpread.EMPTY, HudRowSpread.of(4, 0, 6, null), "no depth");
        assertSame(HudRowSpread.EMPTY, HudRowSpread.of(4, 2, 0, null), "no document");
        assertEquals(0, HudRowSpread.EMPTY.used());
        assertEquals(0, HudRowSpread.EMPTY.depth());
        assertEquals(0, HudRowSpread.EMPTY.rowsOf(0).length);
    }

    @Test
    void aCutColumnKeepsWhatFitsUnderTheUncutTopAndSpillsTheRestFromTheFirstColumnOutward() {
        // Twenty rows dealt 7 / 7 / 6 on a nine-deep document, the third column cut three: its six
        // would reach row 9 above the cut while the others stop at row 7, so it keeps four and the
        // last two go into row 8, one to the first column and one to the second.
        HudRowSpread spread = HudRowSpread.of(20, 7, 9, new HudSpotCutout(3, 3));

        assertEquals(3, spread.used());
        assertEquals(8, spread.depth(), "seven and the spilled row");
        assertArrayEquals(new int[] {0, 1, 2, 3, 4, 5, 6, 18}, spread.rowsOf(0));
        assertArrayEquals(new int[] {7, 8, 9, 10, 11, 12, 13, 19}, spread.rowsOf(1));
        assertArrayEquals(new int[] {14, 15, 16, 17}, spread.rowsOf(2), "rows 4 to 7 above its three empty cells");
    }

    @Test
    void anOverflowOfExactlyOneRowFillsThatRowAcrossEveryColumn() {
        // Eighteen rows dealt six each, the third column cut three: it keeps three, and its other
        // three fill row 7 across all three columns, the cut column included, whose top by then
        // sits just under that row.
        HudRowSpread spread = HudRowSpread.of(18, 6, 9, new HudSpotCutout(3, 3));

        assertEquals(7, spread.depth());
        assertArrayEquals(new int[] {0, 1, 2, 3, 4, 5, 15}, spread.rowsOf(0));
        assertArrayEquals(new int[] {6, 7, 8, 9, 10, 11, 16}, spread.rowsOf(1));
        assertArrayEquals(new int[] {12, 13, 14, 17}, spread.rowsOf(2));
    }

    @Test
    void anOverflowOfMoreThanOneRowOpensAnotherRowAboveTheSameWay() {
        // Eighteen rows dealt six each, the third column cut five: it keeps one, and its other
        // five need two rows above the block, row 7 across all three columns and row 8 across the
        // first two.
        HudRowSpread spread = HudRowSpread.of(18, 6, 9, new HudSpotCutout(3, 5));

        assertEquals(8, spread.depth());
        assertArrayEquals(new int[] {0, 1, 2, 3, 4, 5, 13, 16}, spread.rowsOf(0));
        assertArrayEquals(new int[] {6, 7, 8, 9, 10, 11, 14, 17}, spread.rowsOf(1));
        assertArrayEquals(new int[] {12, 15}, spread.rowsOf(2), "rows 6 and 7 above its five empty cells");
    }

    @Test
    void noOverflowWhenTheCutColumnFitsUnderTheLine() {
        // The same twenty rows with the third column cut one: six rows above one empty cell reach
        // row 7, exactly the others' top, so nothing moves.
        HudRowSpread spread = HudRowSpread.of(20, 7, 9, new HudSpotCutout(3, 1));

        assertEquals(7, spread.depth());
        assertArrayEquals(range(0, 6), spread.rowsOf(0));
        assertArrayEquals(range(7, 13), spread.rowsOf(1));
        assertArrayEquals(range(14, 19), spread.rowsOf(2));
    }

    @Test
    void aRowTheDocumentHasNoCellForIsUndrawn() {
        // Twenty-seven rows fill a nine-deep document nine each; the cut column keeps six, and its
        // other three would need a tenth row, which no column has.
        HudRowSpread spread = HudRowSpread.of(27, 9, 9, new HudSpotCutout(3, 3));

        assertEquals(9, spread.depth());
        assertArrayEquals(range(0, 8), spread.rowsOf(0));
        assertArrayEquals(range(9, 17), spread.rowsOf(1));
        assertArrayEquals(range(18, 23), spread.rowsOf(2));
        TreeSet<Integer> drawn = new TreeSet<>();
        for (int column = 0; column < spread.used(); column++) {
            for (int index : spread.rowsOf(column)) {
                drawn.add(index);
            }
        }
        assertEquals(24, drawn.size());
        assertFalse(drawn.contains(24) || drawn.contains(25) || drawn.contains(26), "the three past the ceiling");
    }

    @Test
    void aCutColumnBelowTheLineTakesASpilledRowOnceItIsAboveItsCut() {
        // Nine rows dealt three each, the third column cut three: none of its rows fits under the
        // others' top, and it is not passed over for good. Row 4 above the block fills the first
        // two columns and then the cut column itself, whose cut ends exactly under it.
        HudRowSpread spread = HudRowSpread.of(9, 3, 9, new HudSpotCutout(3, 3));

        assertEquals(3, spread.used());
        assertEquals(4, spread.depth());
        assertArrayEquals(new int[] {0, 1, 2, 6}, spread.rowsOf(0));
        assertArrayEquals(new int[] {3, 4, 5, 7}, spread.rowsOf(1));
        assertArrayEquals(new int[] {8}, spread.rowsOf(2), "its one row sits in row 4, above the cut");
    }

    @Test
    void aCutColumnLeftWithNothingAtTheFarEndIsNotInUse() {
        // Five rows dealt 2 / 2 / 1, the third column cut three: its one row cannot sit above a
        // three-cell cut beside two-row columns, so it moves to row 3 of the first column and the
        // third column, holding nothing, is not counted, so the frame is two columns wide.
        HudRowSpread spread = HudRowSpread.of(5, 2, 9, new HudSpotCutout(3, 3));

        assertEquals(2, spread.used());
        assertEquals(3, spread.depth());
        assertArrayEquals(new int[] {0, 1, 4}, spread.rowsOf(0));
        assertArrayEquals(new int[] {2, 3}, spread.rowsOf(1));
        assertEquals(0, spread.rowsOf(2).length);
    }

    @Test
    void aColumnEmptiedBetweenTwoThatDrawKeepsItsPlace() {
        // Five rows dealt 2 / 2 / 1 on a six-deep document, the second column cut nine: it has no
        // cell, its two rows go up the first column, and the third column still holds its one, so
        // the empty second column stays where it is rather than pulling the third over.
        HudRowSpread spread = HudRowSpread.of(5, 2, 6, new HudSpotCutout(2, 9));

        assertEquals(3, spread.used());
        assertEquals(4, spread.depth());
        assertArrayEquals(new int[] {0, 1, 2, 3}, spread.rowsOf(0));
        assertEquals(0, spread.rowsOf(1).length);
        assertArrayEquals(new int[] {4}, spread.rowsOf(2));
    }

    @Test
    void aSpillPassesOverAShortLastColumn() {
        // Twenty rows dealt 7 / 7 / 6 with the FIRST column cut three: it keeps four, and its three
        // surplus rows go above the block from the first column outward. The short third column,
        // one row under the line, never takes one: a row there would float over a hole. Row 8 fills
        // the first two columns, row 9 the first.
        HudRowSpread spread = HudRowSpread.of(20, 7, 9, new HudSpotCutout(1, 3));

        assertEquals(8, spread.depth());
        assertArrayEquals(new int[] {0, 1, 2, 3, 4, 6}, spread.rowsOf(0), "rows 4 to 9 above its cut");
        assertArrayEquals(new int[] {7, 8, 9, 10, 11, 12, 13, 5}, spread.rowsOf(1));
        assertArrayEquals(range(14, 19), spread.rowsOf(2), "untouched");
    }

    @Test
    void aPanelWhoseOnlyColumnIsCutHasNoLineToKeepUnderAndDrawsWhatItsCellsHold() {
        HudRowSpread squeezed = HudRowSpread.of(4, 4, 6, new HudSpotCutout(1, 3));
        assertEquals(1, squeezed.used());
        assertArrayEquals(new int[] {0, 1, 2}, squeezed.rowsOf(0), "six cells less three: the fourth row is undrawn");

        HudRowSpread roomy = HudRowSpread.of(4, 4, 9, new HudSpotCutout(1, 3));
        assertArrayEquals(range(0, 3), roomy.rowsOf(0), "nine cells less three holds all four");
    }

    @Test
    void theSpreadKnowsNoCornerSoTheFirstColumnIsTheOneTheCutCountsFrom() {
        // Ordinal 0 is the column at the spot's origin whichever edge the panel is pinned to; the
        // paint maps it onto the rightmost slot column at a right-pinned spot, and the cut's Column
        // 1 names the same column. A spill therefore fills from the origin outward on either side.
        HudRowSpread spread = HudRowSpread.of(9, 3, 9, new HudSpotCutout(1, 2));

        assertEquals(4, spread.depth());
        assertArrayEquals(new int[] {0, 1}, spread.rowsOf(0),
                "the cut column at the origin keeps the one row under the line and takes the first cell of the row above");
        assertArrayEquals(new int[] {3, 4, 5, 2}, spread.rowsOf(1), "the next cell of that row is at ordinal 1, beside the origin");
        assertArrayEquals(new int[] {6, 7, 8}, spread.rowsOf(2), "untouched");
    }
}
