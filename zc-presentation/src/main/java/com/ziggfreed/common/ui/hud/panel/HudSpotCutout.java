package com.ziggfreed.common.ui.hud.panel;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;

/**
 * The cells a spot leaves EMPTY at the pinned end of ONE column, as a FILE states it: which column,
 * and how many of its cells nearest the pinned edge stay clear. A client draws its own things at
 * the screen's edges (at the bottom, the utility slot and the hotbar's end sit under the far column
 * of a three-wide panel), and the cut lets the panel step around one of them without moving the
 * whole panel away. Every leaf is optional, so a file restates only what it wants different from
 * the layer below it, and {@link #over} folds two, leaf by leaf.
 *
 * <p>{@code Column} is ONE-BASED and counts FROM THE PANEL'S OWN FIRST COLUMN, the one at the
 * spot's origin: at a Left spot column 1 is the leftmost, at a Right spot it is the rightmost,
 * because a right-pinned panel opens its columns leftward. The number follows the direction the
 * panel fills, exactly as {@link HudSpotGap}'s {@code AfterRow} follows the pinned edge, so it keeps
 * its meaning if the spot is later pinned to the other side. {@code Rows} is how many of that
 * column's cells nearest the pinned edge stay empty; its rows start above (or, at a Top spot,
 * below) the cut, and they stop where the columns without a cut stop, so the cut column never
 * stands taller than the rest: the rows it no longer has room for go into a row of their own above
 * the block, filled from the panel's first column outward, and into another above that when one is
 * not enough ({@link HudRowSpread}). A row past the document's depth is undrawn.
 *
 * <p>One group, shared by {@link HudSpotAsset} (where a spot leaves its cut) and
 * {@link HudPanelAsset} (an inline restatement over the spot a panel names), so the two files
 * spell it the same way and an author learns it once. Whether a folded group APPLIES at all
 * ({@link #applies}: both numbers present and positive) is the paint's guard, not the fold's, so a
 * file switching the cut off with {@code "Rows": 0} still folds leaf by leaf; a {@code Column} no
 * in-use column reaches simply cuts nothing.
 */
public final class HudSpotCutout {

    @Nullable protected Integer column;
    @Nullable protected Integer rows;

    public static final BuilderCodec<HudSpotCutout> CODEC = BuilderCodec
            .builder(HudSpotCutout.class, HudSpotCutout::new)
            .appendInherited(new KeyedCodec<>("Column", Codec.INTEGER, false),
                    (o, v) -> o.column = v, o -> o.column, (o, p) -> o.column = p.column)
            .documentation("Which column is cut, counted from the panel's own first column, the one at "
                    + "the spot's origin: 1 is the column nearest the spot's pinned side, so at a Right "
                    + "spot 1 is the rightmost. Zero, or a column the rows never open, cuts nothing. "
                    + "Left out, the layer below decides.")
            .add()
            .appendInherited(new KeyedCodec<>("Rows", Codec.INTEGER, false),
                    (o, v) -> o.rows = v, o -> o.rows, (o, p) -> o.rows = p.rows)
            .documentation("How many of that column's cells nearest the pinned edge stay empty. Its rows "
                    + "start past the cut and stop where the columns without a cut stop, so it never "
                    + "stands taller than the rest: the rows it has no room for go into a row of their "
                    + "own above the block, filled from the panel's first column outward, and a row "
                    + "past the document's depth is left undrawn. Zero cuts nothing. Left out, the "
                    + "layer below decides.")
            .add()
            .build();

    public HudSpotCutout() {
    }

    /** A cut with these two leaves, for code assembling one; a null leaf stays unauthored. */
    public HudSpotCutout(@Nullable Integer column, @Nullable Integer rows) {
        this.column = column;
        this.rows = rows;
    }

    /** Which column is cut, one-based from the panel's first; 0 when unauthored or not positive. */
    public int column() {
        return column == null || column <= 0 ? 0 : column;
    }

    /** How many cells nearest the pinned edge stay empty; 0 when unauthored or not positive. */
    public int rows() {
        return rows == null || rows <= 0 ? 0 : rows;
    }

    /** The Column leaf exactly as the file states it, zero included, or null when it states none. */
    @Nullable
    public Integer authoredColumn() {
        return column;
    }

    /** The Rows leaf exactly as the file states it, zero included, or null when it states none. */
    @Nullable
    public Integer authoredRows() {
        return rows;
    }

    /** True when both numbers are stated and positive, so there is a cut to leave. */
    public boolean applies() {
        return column() > 0 && rows() > 0;
    }

    /**
     * How many cells stay empty at the pinned end of the column at {@code ordinal} (zero-based from
     * the panel's own first column): {@code Rows} for the one column the cut names, 0 for every
     * other and whenever the cut does not apply.
     */
    public int rowsAt(int ordinal) {
        return applies() && column() == ordinal + 1 ? rows() : 0;
    }

    /** True when no leaf is authored, so folding this changes nothing. */
    public boolean isEmpty() {
        return column == null && rows == null;
    }

    /**
     * These leaves folded over {@code under}: each authored leaf replaces {@code under}'s, an
     * unauthored one keeps it, and a null {@code under} contributes nothing.
     */
    @Nonnull
    public HudSpotCutout over(@Nullable HudSpotCutout under) {
        return new HudSpotCutout(
                column != null ? column : under != null ? under.column : null,
                rows != null ? rows : under != null ? under.rows : null);
    }

    @Override
    public String toString() {
        return "HudSpotCutout{column=" + column + ", rows=" + rows + "}";
    }
}
