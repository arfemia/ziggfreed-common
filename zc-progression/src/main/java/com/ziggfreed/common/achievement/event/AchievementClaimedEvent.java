package com.ziggfreed.common.achievement.event;

import java.util.List;
import java.util.UUID;

import javax.annotation.Nonnull;

import com.hypixel.hytale.event.IEvent;

/**
 * An achievement's rewards have been paid out, and it is settled.
 *
 * <p>Synchronous {@code IEvent<Void>} POJO on the shared engine event bus; see
 * {@link AchievementEvents} for the fire contract.
 *
 * <p>Fires once per payout: immediately after {@link AchievementUnlockedEvent} for an achievement
 * with nothing to come back for, and later - whenever the subject collects - for one that waited.
 * The counts say how the payout went, so a listener can react to a partial delivery instead of
 * assuming everything landed.
 */
public final class AchievementClaimedEvent implements IEvent<Void> {

    private final String achievementId;
    private final UUID playerId;
    private final int rewardsGranted;
    private final int rewardsQueued;
    private final int rewardsFailed;
    private final List<String> tags;

    public AchievementClaimedEvent(@Nonnull String achievementId, @Nonnull UUID playerId,
                                   int rewardsGranted, int rewardsQueued, int rewardsFailed,
                                   @Nonnull List<String> tags) {
        this.achievementId = achievementId;
        this.playerId = playerId;
        this.rewardsGranted = rewardsGranted;
        this.rewardsQueued = rewardsQueued;
        this.rewardsFailed = rewardsFailed;
        this.tags = List.copyOf(tags);
    }

    @Nonnull
    public String achievementId() {
        return achievementId;
    }

    @Nonnull
    public UUID playerId() {
        return playerId;
    }

    /** Rewards that reached the subject during this payout. */
    public int rewardsGranted() {
        return rewardsGranted;
    }

    /** Rewards that failed but are queued to be retried on the subject's next connect. */
    public int rewardsQueued() {
        return rewardsQueued;
    }

    /** Rewards that could be neither granted nor queued. */
    public int rewardsFailed() {
        return rewardsFailed;
    }

    @Nonnull
    public List<String> tags() {
        return tags;
    }
}
