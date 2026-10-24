package com.ziggfreed.common.instance.dungeon.layout;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * What {@link LayoutGenerator#generate} made of a plan and a seed: the layout, or why there is none.
 *
 * @param layout  the layout, or null when no try at any relax step found one
 * @param tries   how many seeds it tried in all
 * @param relax   how far the plan was relaxed to fit (the last step tried when it failed)
 * @param failure why the last try failed, for the log; null on success
 */
public record LayoutResult(@Nullable Layout layout, int tries, @Nonnull Relax relax, @Nullable String failure) {

    /**
     * How far a plan was relaxed, in the fixed order the generator relaxes it, each step keeping the last's.
     */
    public enum Relax {
        /** The plan as written. */
        NONE,
        /** Every optional branch dropped (one whose count may be 0 or that is left to chance). */
        OPTIONAL_BRANCHES_DROPPED,
        /** Every beat, connector and kept branch at its least count. */
        COUNTS_AT_MINIMUM
    }

    public boolean ok() {
        return layout != null;
    }
}
