package com.ziggfreed.common.world.pattern;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import javax.annotation.Nonnull;

/**
 * A compiled block pattern: an ordered list of {@link PatternCell cells} (anchor-relative integer
 * offsets plus opaque caller payloads) with exactly one designated ANCHOR cell, expanded into up to
 * eight precomputed {@link PatternVariant variants} (four yaw quarter-turns, each optionally
 * X-mirrored). The caller builds cells from its own data; this class never parses anything.
 *
 * <p><b>Normalization.</b> {@link #compile} re-bases every authored offset so the anchor cell sits
 * at the origin: cell order and the anchor INDEX are preserved, but from compile onward every
 * offset is relative to the anchor, and rotation and mirroring both pivot on it (the anchor stays
 * at {@code (0, 0, 0)} in every variant, so a variant's match position IS its anchor position).
 *
 * <p><b>Rotation convention.</b> A yaw quarter-turn is the discrete 0/90/180/270 model about the
 * vertical axis, and one POSITIVE quarter-turn maps an offset {@code (x, y, z)} to
 * {@code (z, y, -x)} - the same turn the engine's yaw {@code Rotation.Ninety} applies to a vector
 * (positive turns carry +Z onto +X). A variant's {@link PatternVariant#yawQuarterTurns()} therefore
 * lines up index-for-index with the engine's {@code Rotation} ordinals (0 = None, 1 = Ninety,
 * 2 = OneEighty, 3 = TwoSeventy), so a consumer can carry a matched rotation straight into a block
 * write. The X-mirror negates the authored X offsets FIRST (a reflection through the anchor's YZ
 * plane), then the quarter-turns apply.
 *
 * <p><b>No variant dedup.</b> Payloads are opaque, so two variants of a symmetric pattern that
 * happen to describe the same world shape cannot be proven identical here; every enabled variant is
 * generated. A pattern that is fully rotation-symmetric simply compiles with {@code rotate} false
 * and skips the redundant walks.
 *
 * <p>Duplicate offsets are legal: the matcher tests every cell, so two cells sharing an offset are
 * two predicates ANDed on one block.
 *
 * <p>Immutable and thread-safe once compiled.
 *
 * @param <P> the caller's own cell payload type
 */
public final class BlockPattern<P> {

    private final List<PatternCell<P>> cells;
    private final int anchorIndex;
    private final int boundingRadius;
    private final List<PatternVariant<P>> variants;

    private BlockPattern(@Nonnull List<PatternCell<P>> normalized, int anchorIndex,
            boolean rotate, boolean mirror) {
        this.cells = List.copyOf(normalized);
        this.anchorIndex = anchorIndex;

        int radius = 0;
        for (PatternCell<P> cell : this.cells) {
            radius = Math.max(radius, Math.max(Math.abs(cell.dx()),
                    Math.max(Math.abs(cell.dy()), Math.abs(cell.dz()))));
        }
        this.boundingRadius = radius;

        List<PatternVariant<P>> built = new ArrayList<>();
        int mirrorArms = mirror ? 2 : 1;
        int yawTurns = rotate ? 4 : 1;
        for (int arm = 0; arm < mirrorArms; arm++) {
            boolean mirrored = arm == 1;
            for (int k = 0; k < yawTurns; k++) {
                built.add(new PatternVariant<>(this, built.size(), k, mirrored));
            }
        }
        this.variants = List.copyOf(built);
    }

    /**
     * Compile authored cells into a pattern. The anchor cell (named by index into {@code cells})
     * may sit anywhere in the authored frame; every offset is re-based so it lands on the origin.
     *
     * @param cells       the authored cells, at least one; order is preserved
     * @param anchorIndex which cell is the anchor
     * @param rotate      expand the four yaw quarter-turn variants (false = identity yaw only)
     * @param mirror      additionally expand each yaw variant X-mirrored
     * @throws IllegalArgumentException on an empty cell list or an out-of-range anchor index
     */
    @Nonnull
    public static <P> BlockPattern<P> compile(@Nonnull List<PatternCell<P>> cells, int anchorIndex,
            boolean rotate, boolean mirror) {
        Objects.requireNonNull(cells, "cells");
        if (cells.isEmpty()) {
            throw new IllegalArgumentException("a pattern needs at least one cell");
        }
        if (anchorIndex < 0 || anchorIndex >= cells.size()) {
            throw new IllegalArgumentException(
                    "anchorIndex " + anchorIndex + " is outside 0.." + (cells.size() - 1));
        }
        PatternCell<P> anchor = cells.get(anchorIndex);
        List<PatternCell<P>> normalized = new ArrayList<>(cells.size());
        for (PatternCell<P> cell : cells) {
            normalized.add(new PatternCell<>(cell.dx() - anchor.dx(), cell.dy() - anchor.dy(),
                    cell.dz() - anchor.dz(), cell.payload()));
        }
        return new BlockPattern<>(normalized, anchorIndex, rotate, mirror);
    }

    /** The normalized cells (anchor at the origin), in authored order. */
    @Nonnull
    public List<PatternCell<P>> cells() {
        return cells;
    }

    /** Which cell is the anchor; its normalized offset is always {@code (0, 0, 0)}. */
    public int anchorIndex() {
        return anchorIndex;
    }

    public int cellCount() {
        return cells.size();
    }

    /** The payload of one cell; the same across every variant (a transform moves offsets only). */
    @Nonnull
    public P payload(int cellIndex) {
        return cells.get(cellIndex).payload();
    }

    /**
     * The precomputed variants, identity first: unmirrored yaws in quarter-turn order, then (when
     * mirroring is enabled) the mirrored yaws in the same order. A variant's index in this list is
     * its {@link PatternVariant#variantIndex()}.
     */
    @Nonnull
    public List<PatternVariant<P>> variants() {
        return variants;
    }

    /**
     * The pattern's Chebyshev radius: the largest absolute offset component over all cells. It is
     * the same for every variant (a yaw turn or X-mirror only permutes and negates components), so
     * a caller checking "could a block this far from a candidate anchor still belong to this
     * pattern?" compares against this one number.
     */
    public int boundingRadius() {
        return boundingRadius;
    }
}
