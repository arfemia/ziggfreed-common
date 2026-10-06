package com.ziggfreed.common.reputation.event;

import java.util.UUID;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.event.IEvent;
import com.ziggfreed.common.reputation.ReputationChange;
import com.ziggfreed.common.reputation.ReputationLadder;

/**
 * A player's EARNED standing with a reputation changed through zc (a reward, a kill): fired once per
 * change, on that player's world thread, after the engine wrote it. Ranks are by effective standing
 * (earned plus gear) before and after; null when the server has no usable ladder. The admin
 * {@code /reputation} command writes the native value directly and raises none.
 */
public final class ZigReputationChangedEvent implements IEvent<Void> {

    private final UUID playerId;
    private final String reputationId;
    private final int earnedBefore;
    private final int earnedAfter;
    private final long effectiveAfter;
    @Nullable private final String rankBefore;
    @Nullable private final String rankAfter;
    private final String source;

    public ZigReputationChangedEvent(@Nonnull UUID playerId, @Nonnull String reputationId, int earnedBefore,
            int earnedAfter, long effectiveAfter, @Nullable String rankBefore, @Nullable String rankAfter,
            @Nonnull String source) {
        this.playerId = playerId;
        this.reputationId = reputationId;
        this.earnedBefore = earnedBefore;
        this.earnedAfter = earnedAfter;
        this.effectiveAfter = effectiveAfter;
        this.rankBefore = rankBefore;
        this.rankAfter = rankAfter;
        this.source = source;
    }

    /** The event for {@code change}. */
    @Nonnull
    public static ZigReputationChangedEvent of(@Nonnull ReputationChange change) {
        return new ZigReputationChangedEvent(change.playerId(), change.reputation().id(), change.earnedBefore(),
                change.earnedAfter(), change.effectiveAfter(), idOf(change.rankBefore()), idOf(change.rankAfter()),
                change.source());
    }

    @Nonnull
    public UUID playerId() {
        return playerId;
    }

    /** The reputation, in the engine's own spelling of its group id. */
    @Nonnull
    public String reputationId() {
        return reputationId;
    }

    public int earnedBefore() {
        return earnedBefore;
    }

    public int earnedAfter() {
        return earnedAfter;
    }

    public long effectiveAfter() {
        return effectiveAfter;
    }

    @Nullable
    public String rankBefore() {
        return rankBefore;
    }

    @Nullable
    public String rankAfter() {
        return rankAfter;
    }

    /** What moved it: {@code quest:<id>}, {@code kill:<role>}, a payout's own source id. */
    @Nonnull
    public String source() {
        return source;
    }

    @Nullable
    private static String idOf(@Nullable ReputationLadder.Rank rank) {
        return rank == null ? null : rank.id();
    }
}
