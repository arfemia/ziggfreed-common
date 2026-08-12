package com.ziggfreed.common.quest.event;

import java.util.List;
import java.util.UUID;

import javax.annotation.Nonnull;

import com.hypixel.hytale.event.IEvent;

/**
 * A quest's rewards have been granted, and the quest is closed out.
 *
 * <p>Synchronous {@code IEvent<Void>} POJO on the shared engine event bus; see {@link QuestEvents}
 * for the fire contract.
 *
 * <p>Fires once per payout: immediately after {@link QuestCompletedEvent} for a quest that pays out
 * by itself, and later - whenever the player collects - for one that parked. The counts say how the
 * payout went, so a listener can react to a partial delivery instead of assuming everything landed.
 */
public final class QuestClaimedEvent implements IEvent<Void> {

    private final String questId;
    private final UUID playerId;
    private final int rewardsGranted;
    private final int rewardsQueued;
    private final int rewardsFailed;
    private final List<String> tags;

    public QuestClaimedEvent(@Nonnull String questId, @Nonnull UUID playerId, int rewardsGranted,
                             int rewardsQueued, int rewardsFailed, @Nonnull List<String> tags) {
        this.questId = questId;
        this.playerId = playerId;
        this.rewardsGranted = rewardsGranted;
        this.rewardsQueued = rewardsQueued;
        this.rewardsFailed = rewardsFailed;
        this.tags = List.copyOf(tags);
    }

    @Nonnull
    public String questId() {
        return questId;
    }

    @Nonnull
    public UUID playerId() {
        return playerId;
    }

    /** Rewards that reached the player during this payout. */
    public int rewardsGranted() {
        return rewardsGranted;
    }

    /** Rewards that failed but are queued to be retried on the player's next connect. */
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
