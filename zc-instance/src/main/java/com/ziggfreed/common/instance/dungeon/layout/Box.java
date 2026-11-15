package com.ziggfreed.common.instance.dungeon.layout;

import javax.annotation.Nonnull;

/** The block cells a room covers, both corners in: {@code min} holds the least of each axis, {@code max} the most. */
public record Box(@Nonnull Cell min, @Nonnull Cell max) {

    public Box {
        Cell lo = new Cell(Math.min(min.x(), max.x()), Math.min(min.y(), max.y()), Math.min(min.z(), max.z()));
        Cell hi = new Cell(Math.max(min.x(), max.x()), Math.max(min.y(), max.y()), Math.max(min.z(), max.z()));
        min = lo;
        max = hi;
    }

    /** The box from the origin cell to {@code size} less one on each axis: a layout's bounds. */
    @Nonnull
    public static Box ofSize(@Nonnull Cell size) {
        return new Box(Cell.ZERO, new Cell(Math.max(0, size.x() - 1), Math.max(0, size.y() - 1),
                Math.max(0, size.z() - 1)));
    }

    /** This box after {@code turns} quarter turns about the anchor. */
    @Nonnull
    public Box turned(int turns) {
        return new Box(min.turned(turns), max.turned(turns));
    }

    @Nonnull
    public Box shifted(@Nonnull Cell by) {
        return new Box(min.plus(by), max.plus(by));
    }

    public boolean contains(@Nonnull Cell cell) {
        return cell.x() >= min.x() && cell.x() <= max.x()
                && cell.y() >= min.y() && cell.y() <= max.y()
                && cell.z() >= min.z() && cell.z() <= max.z();
    }

    /** Whether the two share a cell. */
    public boolean overlaps(@Nonnull Box other) {
        return min.x() <= other.max.x() && max.x() >= other.min.x()
                && min.y() <= other.max.y() && max.y() >= other.min.y()
                && min.z() <= other.max.z() && max.z() >= other.min.z();
    }

    /** Whether every cell of this box lies in {@code outer}. */
    public boolean within(@Nonnull Box outer) {
        return outer.contains(min) && outer.contains(max);
    }

    public long volume() {
        return (long) (max.x() - min.x() + 1) * (max.y() - min.y() + 1) * (max.z() - min.z() + 1);
    }
}
