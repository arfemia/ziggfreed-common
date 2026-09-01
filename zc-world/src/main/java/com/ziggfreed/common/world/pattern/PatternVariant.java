package com.ziggfreed.common.world.pattern;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.joml.Vector3i;

/**
 * One orientation of a {@link BlockPattern}: the anchor-relative cell offsets with the variant's
 * X-mirror and yaw quarter-turns applied (mirror first, then the turns; see the rotation
 * convention on {@link BlockPattern}). Cell INDEXES are stable across variants - cell {@code i}
 * keeps its authored payload in every orientation, only its offset moves - and the anchor cell's
 * offset is {@code (0, 0, 0)} in every variant.
 *
 * <p>Matching is a short-circuit walk: cells are tested in authored order, a cell whose position
 * cannot be read fails immediately (an unloaded section never matches and is never loaded), and
 * the first failing cell ends the walk.
 *
 * @param <P> the caller's own cell payload type
 */
public final class PatternVariant<P> {

    private final BlockPattern<P> pattern;
    private final int variantIndex;
    private final int yawQuarterTurns;
    private final boolean mirrored;
    private final int[] dx;
    private final int[] dy;
    private final int[] dz;

    PatternVariant(@Nonnull BlockPattern<P> pattern, int variantIndex, int yawQuarterTurns,
            boolean mirrored) {
        this.pattern = pattern;
        this.variantIndex = variantIndex;
        this.yawQuarterTurns = yawQuarterTurns;
        this.mirrored = mirrored;

        int count = pattern.cellCount();
        this.dx = new int[count];
        this.dy = new int[count];
        this.dz = new int[count];
        for (int i = 0; i < count; i++) {
            PatternCell<P> cell = pattern.cells().get(i);
            int x = mirrored ? -cell.dx() : cell.dx();
            int y = cell.dy();
            int z = cell.dz();
            for (int k = 0; k < yawQuarterTurns; k++) {
                int rotatedX = z;
                int rotatedZ = -x;
                x = rotatedX;
                z = rotatedZ;
            }
            this.dx[i] = x;
            this.dy[i] = y;
            this.dz[i] = z;
        }
    }

    @Nonnull
    public BlockPattern<P> pattern() {
        return pattern;
    }

    /** This variant's index in {@link BlockPattern#variants()}. */
    public int variantIndex() {
        return variantIndex;
    }

    /**
     * How many positive quarter-turns about the vertical axis this variant applies (0..3). Lines
     * up with the engine's yaw {@code Rotation} ordinals, so a consumer carries a matched rotation
     * into a block write as the rotation with degrees {@code 90 * yawQuarterTurns}.
     */
    public int yawQuarterTurns() {
        return yawQuarterTurns;
    }

    /** Whether this variant reflects the authored X offsets through the anchor's YZ plane. */
    public boolean mirrored() {
        return mirrored;
    }

    public int cellCount() {
        return dx.length;
    }

    /** This variant's X offset for one cell, relative to the anchor. */
    public int dx(int cellIndex) {
        return dx[cellIndex];
    }

    /** This variant's Y offset for one cell, relative to the anchor. */
    public int dy(int cellIndex) {
        return dy[cellIndex];
    }

    /** This variant's Z offset for one cell, relative to the anchor. */
    public int dz(int cellIndex) {
        return dz[cellIndex];
    }

    /**
     * Walk every cell from this candidate anchor position, short-circuiting on the first fail. A
     * cell fails when its position cannot be read (the reader answers null) or when the caller's
     * predicate rejects the block read there; the anchor cell is tested like any other.
     */
    public boolean matchAt(int anchorX, int anchorY, int anchorZ, @Nonnull BlockReader reader,
            @Nonnull CellPredicate<P> predicate) {
        for (int i = 0; i < dx.length; i++) {
            String blockItemId = reader.blockItemIdAt(anchorX + dx[i], anchorY + dy[i], anchorZ + dz[i]);
            if (blockItemId == null || !predicate.test(pattern.payload(i), blockItemId)) {
                return false;
            }
        }
        return true;
    }

    /**
     * The anchor position implied by one cell of this variant standing at a known world position:
     * a caller holding "this placed block would be cell {@code cellIndex}" derives where the
     * anchor must be before walking the rest. A fresh vector the caller owns.
     */
    @Nonnull
    public Vector3i anchorFromCell(int cellIndex, int cellX, int cellY, int cellZ) {
        return new Vector3i(cellX - dx[cellIndex], cellY - dy[cellIndex], cellZ - dz[cellIndex]);
    }

    /**
     * {@link #anchorFromCell} + {@link #matchAt} in one step: derive the implied anchor from a
     * known cell position, walk the cells, and answer the completed {@link PatternMatch} - or null
     * when the walk fails. Probing outward from the anchor cell itself is this call with
     * {@code cellIndex = pattern().anchorIndex()}.
     */
    @Nullable
    public PatternMatch<P> matchFromCell(int cellIndex, int cellX, int cellY, int cellZ,
            @Nonnull BlockReader reader, @Nonnull CellPredicate<P> predicate) {
        int anchorX = cellX - dx[cellIndex];
        int anchorY = cellY - dy[cellIndex];
        int anchorZ = cellZ - dz[cellIndex];
        if (!matchAt(anchorX, anchorY, anchorZ, reader, predicate)) {
            return null;
        }
        return new PatternMatch<>(pattern, variantIndex, anchorX, anchorY, anchorZ);
    }
}
