package com.ziggfreed.common.instance.dungeon.layout;

import javax.annotation.Nonnull;

/** A block cell, in a room's own frame (relative to its prefab anchor) or the layout's. */
public record Cell(int x, int y, int z) {

    public static final Cell ZERO = new Cell(0, 0, 0);

    @Nonnull
    public Cell plus(@Nonnull Cell other) {
        return new Cell(x + other.x, y + other.y, z + other.z);
    }

    @Nonnull
    public Cell minus(@Nonnull Cell other) {
        return new Cell(x - other.x, y - other.y, z - other.z);
    }

    /** The cell one step {@code facing}'s way. */
    @Nonnull
    public Cell step(@Nonnull Facing facing) {
        return new Cell(x + facing.dx(), y, z + facing.dz());
    }

    /** This cell after {@code turns} quarter turns about the anchor ({@link QuarterTurns}). */
    @Nonnull
    public Cell turned(int turns) {
        return new Cell(QuarterTurns.x(x, z, turns), y, QuarterTurns.z(x, z, turns));
    }
}
