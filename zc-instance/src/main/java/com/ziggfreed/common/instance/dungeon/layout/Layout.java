package com.ziggfreed.common.instance.dungeon.layout;

import java.util.List;

import javax.annotation.Nonnull;

/**
 * A placed layout: every room a run pastes, where it stands, and how its doors join. Immutable and compared by
 * value, so two layouts from one seed are {@code equals}.
 *
 * @param planId     the plan it was made from, folded
 * @param seed       the run seed it was made from
 * @param strata     the tag the seed picked for each stratum, in the plan's order ("" for a stratum listing none)
 * @param pathLength how many rooms stand on the path, the entry included; they are the first rooms of the list
 * @param rooms      every room, the path's first in path order, then branches, needs and caps
 */
public record Layout(@Nonnull String planId, long seed, @Nonnull List<String> strata, int pathLength,
                     @Nonnull List<PlacedRoom> rooms) {

    public Layout {
        strata = List.copyOf(strata);
        rooms = List.copyOf(rooms);
    }

    @Nonnull
    public PlacedRoom room(int index) {
        return rooms.get(index);
    }

    /**
     * Whether a party walking the path reaches room {@code a} before room {@code b}: {@code a} stands earlier along
     * the path, or both share a place along it and {@code a} is that path room itself or stands earlier on the same
     * branch. Rooms on two branches off one path room come in neither order.
     */
    public boolean isBefore(int a, int b) {
        return isBefore(rooms.get(a), rooms.get(b));
    }

    /** As {@link #isBefore(int, int)} for two placed rooms; the one rule the generator and its checks share. */
    public static boolean isBefore(@Nonnull PlacedRoom a, @Nonnull PlacedRoom b) {
        return before(a.pathIndex(), a.onPath(), a.branch(), a.step(), b.pathIndex(), b.onPath(), b.branch(), b.step());
    }

    static boolean before(int aPath, boolean aOnPath, int aBranch, int aStep,
            int bPath, boolean bOnPath, int bBranch, int bStep) {
        if (aPath != bPath) {
            return aPath < bPath;
        }
        if (aOnPath) {
            return !bOnPath;
        }
        return !bOnPath && aBranch == bBranch && aStep < bStep;
    }
}
