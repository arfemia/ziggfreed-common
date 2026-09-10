package com.ziggfreed.common.ui.hud.bar;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;

/**
 * A band left clear across a panel, as a FILE states it: after how many rows, and how tall. At a
 * spot pinned to the bottom edge the rows climb into the player's own health and mana bars and then
 * the hotbar, and the band lets the stack straddle that cluster, some rows below it and the rest
 * above. Every leaf is optional, so a file restates only what it wants different from the layer
 * below it, and {@link #over} folds two, leaf by leaf.
 *
 * <p>{@code AfterRow} counts FROM THE PINNED EDGE: row 1 is the row against the spot's own edge, so
 * at a Bottom spot {@code AfterRow: 3} is three rows sitting above the hotbar, the band, then the
 * rest climbing above the mana bar, and the same number keeps meaning the same thing at a Top spot.
 * The band runs across every column at the same height, so the rows read as one grid with a clean
 * break through it; a column with no row past {@code AfterRow} draws no band, because there would
 * be nothing above it.
 *
 * <p>One group, shared by {@link HudBarPlacementAsset} (where a spot leaves its band) and
 * {@link HudBarPanelAsset} (an inline restatement over the spot a panel names), so the two files
 * spell it the same way and an author learns it once. Whether a folded group APPLIES at all
 * ({@link #applies}: both numbers present and positive) is the paint's guard, not the fold's, so a
 * file switching the band off with {@code "Pixels": 0} still folds leaf by leaf.
 */
public final class HudBarGap {

    @Nullable protected Integer afterRow;
    @Nullable protected Integer pixels;

    public static final BuilderCodec<HudBarGap> CODEC = BuilderCodec
            .builder(HudBarGap.class, HudBarGap::new)
            .appendInherited(new KeyedCodec<>("AfterRow", Codec.INTEGER, false),
                    (o, v) -> o.afterRow = v, o -> o.afterRow, (o, p) -> o.afterRow = p.afterRow)
            .documentation("How many rows sit against the spot's pinned edge before the band: row 1 "
                    + "is the row nearest that edge, so at a Bottom spot 3 keeps three rows below the "
                    + "band and the rest above it. Zero, or a number no column reaches past, draws no "
                    + "band. Left out, the layer below decides.")
            .add()
            .appendInherited(new KeyedCodec<>("Pixels", Codec.INTEGER, false),
                    (o, v) -> o.pixels = v, o -> o.pixels, (o, p) -> o.pixels = p.pixels)
            .documentation("How tall the band is, in pixels. Zero draws no band. Left out, the layer "
                    + "below decides.")
            .add()
            .build();

    public HudBarGap() {
    }

    /** A band with these two leaves, for code assembling one; a null leaf stays unauthored. */
    public HudBarGap(@Nullable Integer afterRow, @Nullable Integer pixels) {
        this.afterRow = afterRow;
        this.pixels = pixels;
    }

    /** How many rows sit against the pinned edge before the band; 0 when unauthored or not positive. */
    public int afterRow() {
        return afterRow == null || afterRow <= 0 ? 0 : afterRow;
    }

    /** How tall the band is; 0 when unauthored or not positive. */
    public int pixels() {
        return pixels == null || pixels <= 0 ? 0 : pixels;
    }

    /** The AfterRow leaf exactly as the file states it, zero included, or null when it states none. */
    @Nullable
    public Integer authoredAfterRow() {
        return afterRow;
    }

    /** The Pixels leaf exactly as the file states it, zero included, or null when it states none. */
    @Nullable
    public Integer authoredPixels() {
        return pixels;
    }

    /** True when both numbers are stated and positive, so there is a band to leave. */
    public boolean applies() {
        return afterRow() > 0 && pixels() > 0;
    }

    /** True when no leaf is authored, so folding this changes nothing. */
    public boolean isEmpty() {
        return afterRow == null && pixels == null;
    }

    /**
     * These leaves folded over {@code under}: each authored leaf replaces {@code under}'s, an
     * unauthored one keeps it, and a null {@code under} contributes nothing.
     */
    @Nonnull
    public HudBarGap over(@Nullable HudBarGap under) {
        return new HudBarGap(
                afterRow != null ? afterRow : under != null ? under.afterRow : null,
                pixels != null ? pixels : under != null ? under.pixels : null);
    }

    @Override
    public String toString() {
        return "HudBarGap{afterRow=" + afterRow + ", pixels=" + pixels + "}";
    }
}
