package com.ziggfreed.common.reputation;

import java.util.UUID;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * One write zc made, as it landed: earned before and after (the engine's clamp applied), the gear share
 * at the time, the effective rank before and after, where effective standing now stands inside its rank,
 * how many Beyond multiples the rise crossed, and what moved it ({@code quest:<id>}, {@code kill:<role>}).
 */
public record ReputationChange(@Nonnull UUID playerId,
                               @Nonnull ReputationDef reputation,
                               int earnedBefore,
                               int earnedAfter,
                               long gear,
                               @Nullable ReputationLadder.Rank rankBefore,
                               @Nullable ReputationLadder.Rank rankAfter,
                               @Nonnull ReputationLadder.Progress progress,
                               int beyondCrossings,
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
}
