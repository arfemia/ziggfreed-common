package com.ziggfreed.common.reputation;

import java.util.UUID;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * One move of a player's standing, as it landed: earned before and after (the worn reputation gain, the Cap and,
 * where the engine keeps the standing, its clamp all applied), the rank before and after, where standing now
 * stands inside its rank, the floor of the reputation's top rank its Beyond counts from (null with no usable
 * ladder), and what moved it ({@code quest:<id>}, {@code kill:<role>}, or the source of a move of the reputation's
 * track, such as a Track reward's; an outside write to the engine's value is folded into the track silently and is
 * never one). The ranks read the held standing, which is the earned one unless the
 * reputation's track was prestiged, when it is at least the floor of the best rank ever reached. The Beyond steps
 * are read from that floor and the reputation's Every ({@link ReputationLadder#beyondSteps}).
 */
public record ReputationChange(@Nonnull UUID playerId,
                               @Nonnull ReputationDef reputation,
                               int earnedBefore,
                               int earnedAfter,
                               @Nullable ReputationLadder.Rank rankBefore,
                               @Nullable ReputationLadder.Rank rankAfter,
                               @Nonnull ReputationLadder.Progress progress,
                               @Nullable Integer beyondFloor,
                               @Nonnull String source,
                               int heldBefore,
                               int heldAfter) {

    /** A move whose ranks read what was earned. */
    public ReputationChange(@Nonnull UUID playerId, @Nonnull ReputationDef reputation, int earnedBefore,
            int earnedAfter, @Nullable ReputationLadder.Rank rankBefore, @Nullable ReputationLadder.Rank rankAfter,
            @Nonnull ReputationLadder.Progress progress, @Nullable Integer beyondFloor, @Nonnull String source) {
        this(playerId, reputation, earnedBefore, earnedAfter, rankBefore, rankAfter, progress, beyondFloor, source,
                earnedBefore, earnedAfter);
    }

    /** What earned standing actually moved by: the gain as it landed, worn bonus included. */
    public int delta() {
        return earnedAfter - earnedBefore;
    }

    /** The standing the ranks read before the change: what was earned (gear adds none), or what a prestige kept. */
    public long effectiveBefore() {
        return heldBefore;
    }

    /** The standing the ranks read after the change: what was earned (gear adds none), or what a prestige kept. */
    public long effectiveAfter() {
        return heldAfter;
    }

    /** The Beyond step earned standing stood on before the change: 0 below the top or with no Beyond. */
    public long beyondStepsBefore() {
        return beyondSteps(earnedBefore);
    }

    /** The Beyond step earned standing stands on after the change: 0 below the top or with no Beyond. */
    public long beyondStepsAfter() {
        return beyondSteps(earnedAfter);
    }

    /** How many Beyond multiples this change newly crossed: 0 for a fall, and below the top. */
    public int beyondCrossings() {
        return (int) Math.max(0L, Math.min(Integer.MAX_VALUE, beyondStepsAfter() - beyondStepsBefore()));
    }

    private long beyondSteps(long earned) {
        return beyondFloor == null ? 0L : ReputationLadder.beyondSteps(earned, beyondFloor, reputation.beyondEvery());
    }
}
