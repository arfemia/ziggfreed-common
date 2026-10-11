package com.ziggfreed.common.reputation;

import java.util.UUID;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * One write zc made, as it landed: earned before and after (the engine's clamp applied), the gear share
 * at the time, the effective rank before and after, where effective standing now stands inside its rank,
 * the floor of the reputation's top rank its Beyond counts from (null with no usable ladder), and what moved
 * it ({@code quest:<id>}, {@code kill:<role>}). The Beyond steps are read from that floor and the reputation's
 * Every ({@link ReputationLadder#beyondSteps}).
 */
public record ReputationChange(@Nonnull UUID playerId,
                               @Nonnull ReputationDef reputation,
                               int earnedBefore,
                               int earnedAfter,
                               long gear,
                               @Nullable ReputationLadder.Rank rankBefore,
                               @Nullable ReputationLadder.Rank rankAfter,
                               @Nonnull ReputationLadder.Progress progress,
                               @Nullable Integer beyondFloor,
                               @Nonnull String source) {

    /** What the engine actually moved earned standing by. */
    public int delta() {
        return earnedAfter - earnedBefore;
    }

    public long effectiveBefore() {
        return earnedBefore + gear;
    }

    public long effectiveAfter() {
        return earnedAfter + gear;
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
