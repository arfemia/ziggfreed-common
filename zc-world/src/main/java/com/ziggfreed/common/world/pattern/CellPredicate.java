package com.ziggfreed.common.world.pattern;

import javax.annotation.Nonnull;

/**
 * The caller's own answer to "does this block satisfy this cell?". The matcher hands over the
 * cell's opaque payload and the block ITEM id it read at the cell's world position (air arrives as
 * the engine's own empty key, {@code "Empty"}, never as null: a position that could not be read at
 * all fails the match before this seam is ever consulted). Implementations decide everything about
 * acceptance - exact id, id family, tag lookup, "must be air" - the library carries no matching
 * vocabulary of its own.
 *
 * @param <P> the caller's own cell payload type
 */
@FunctionalInterface
public interface CellPredicate<P> {

    /** True when the block read at a cell's position satisfies the cell's payload. */
    boolean test(@Nonnull P payload, @Nonnull String blockItemId);
}
