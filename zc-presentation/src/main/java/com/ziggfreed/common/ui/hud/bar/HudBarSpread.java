package com.ziggfreed.common.ui.hud.bar;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Which row each in-use column draws once the rows are split across a spot's columns: the lattice
 * every column's shape ({@link HudBarHud.ColumnShape}) and every slot's row are read from. Rows are
 * dealt COLUMN BY COLUMN, {@code perColumn} to each and the last column short, and with no cut
 * anywhere that is the whole answer: the column at ordinal {@code c} draws rows
 * {@code c * perColumn} onward, exactly as many as the document has cells for.
 *
 * <p><b>No column stands taller than the columns that have no cut.</b> A cut column's rows start
 * above its cut, so given its full share it would reach past the others and leave empty frame over
 * them. Instead it keeps only the rows that fit under the COMMON TOP LINE, the tallest column
 * without a cut, and the rows that no longer fit move into a row of their own ABOVE the block,
 * filled from the panel's FIRST column outward (ordinal 0, the column at the spot's origin, which
 * at a right-pinned spot is the rightmost: the spread knows no corner, so "first" is the same
 * column the cut counts from), then into the next row above that one, until every row is placed or
 * the document's depth runs out; a row nothing can hold is undrawn, as a row past the document's
 * ceiling always was. A spilled row belongs to the column it lands in and is drawn by that column's
 * own slots: the spill changes which row a slot draws, never how a slot is painted.
 *
 * <p>A row above the block goes only where it stacks on what is already there: a column whose top
 * sits just under that row takes it, a short last column (its top lower still) is passed over, and
 * a cut column takes one only once the row is above its cut. A panel whose only in-use column is the
 * cut one has no line to keep under and draws what its cells hold. A column left with nothing at
 * the far end (a cut column whose every row moved) is not in use at all; one emptied between two
 * that draw keeps its place, so its neighbours stay where they are.
 *
 * <p>Pure: counts in, row indices out, no engine type anywhere, so the whole spread is checkable in
 * a unit JVM.
 *
 * @param columns the row indices each in-use column draws, by ordinal from the panel's first
 *                column, each array in ordinal order from the pinned edge
 */
record HudBarSpread(@Nonnull List<int[]> columns) {

    /** Nothing showing: no column and no depth. */
    static final HudBarSpread EMPTY = new HudBarSpread(List.of());

    private static final int[] NO_ROWS = new int[0];

    /**
     * Spread {@code rowCount} rows {@code perColumn} deep over a document {@code slotsPerColumn}
     * deep, with {@code cutout} (null or not applying for none) leaving cells empty at the pinned
     * end of one column.
     */
    @Nonnull
    static HudBarSpread of(int rowCount, int perColumn, int slotsPerColumn, @Nullable HudBarCutout cutout) {
        if (rowCount <= 0 || perColumn <= 0 || slotsPerColumn <= 0) {
            return EMPTY;
        }
        // The split: perColumn to each column in turn, the last one short.
        int used = HudBarHud.usedColumnsFor(rowCount, perColumn);
        List<Integer> given = new ArrayList<>(used);
        int[] cut = new int[used];
        for (int c = 0; c < used; c++) {
            given.add(Math.min(perColumn, rowCount - c * perColumn));
            cut[c] = cutout == null ? 0 : cutout.rowsAt(c);
        }
        // The common top line: the tallest column with no cut. With none in use there is no line
        // to keep under, and the document's depth is the only ceiling either way.
        int top = 0;
        for (int c = 0; c < used; c++) {
            if (cut[c] == 0) {
                top = Math.max(top, given.get(c));
            }
        }
        top = Math.min(top == 0 ? slotsPerColumn : top, slotsPerColumn);
        // Each column keeps what fits under that line past its cut, in order; the rest waits to spill.
        List<List<Integer>> rows = new ArrayList<>(used);
        Deque<Integer> spill = new ArrayDeque<>();
        int first = 0;
        for (int c = 0; c < used; c++) {
            int share = given.get(c);
            int keep = Math.max(0, Math.min(share, top - cut[c]));
            List<Integer> mine = new ArrayList<>(share);
            for (int i = 0; i < share; i++) {
                if (i < keep) {
                    mine.add(first + i);
                } else {
                    spill.add(first + i);
                }
            }
            rows.add(mine);
            first += share;
        }
        // The spill: one row above the line at a time, from the first column outward, into every
        // column whose rows reach just under it, until nothing is left or the document runs out.
        for (int level = top + 1; !spill.isEmpty() && level <= slotsPerColumn; level++) {
            for (int c = 0; c < used && !spill.isEmpty(); c++) {
                if (cut[c] + rows.get(c).size() == level - 1) {
                    rows.get(c).add(spill.poll());
                }
            }
        }
        int last = used;
        while (last > 0 && rows.get(last - 1).isEmpty()) {
            last--;
        }
        List<int[]> columns = new ArrayList<>(last);
        for (int c = 0; c < last; c++) {
            columns.add(rows.get(c).stream().mapToInt(Integer::intValue).toArray());
        }
        return new HudBarSpread(List.copyOf(columns));
    }

    /** How many columns hold a row, counting through the last one that does. */
    int used() {
        return columns.size();
    }

    /**
     * The tallest column's count: the depth every column's slots are mapped at
     * ({@link HudBarHud#ordinalInColumn}), so a column shorter than it hides its surplus slots.
     */
    int depth() {
        int depth = 0;
        for (int[] column : columns) {
            depth = Math.max(depth, column.length);
        }
        return depth;
    }

    /**
     * The row indices the column at {@code ordinal} draws, its first nearest the pinned edge; empty
     * for a column past the last in use or one left with nothing.
     */
    @Nonnull
    int[] rowsOf(int ordinal) {
        return ordinal < 0 || ordinal >= columns.size() ? NO_ROWS : columns.get(ordinal);
    }
}
