package com.ziggfreed.common.quest.event;

import java.util.List;
import java.util.UUID;

import javax.annotation.Nonnull;

import com.hypixel.hytale.event.IEvent;

/**
 * Every objective on a quest is now met.
 *
 * <p>Synchronous {@code IEvent<Void>} POJO on the shared engine event bus; see {@link QuestEvents}
 * for the fire contract.
 *
 * <p><b>This is about the OBJECTIVES, not the reward.</b> It fires the moment the last objective
 * completes, whether the quest pays out immediately or parks for the player to collect. Use
 * {@code parked} to tell the two apart, and listen for {@link QuestClaimedEvent} if what you care
 * about is the payout actually happening.
 */
public final class QuestCompletedEvent implements IEvent<Void> {

    private final String questId;
    private final UUID playerId;
    private final boolean parked;
    private final List<String> tags;

    public QuestCompletedEvent(@Nonnull String questId, @Nonnull UUID playerId, boolean parked,
                               @Nonnull List<String> tags) {
        this.questId = questId;
        this.playerId = playerId;
        this.parked = parked;
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

    /** True when the reward is waiting to be claimed rather than already granted. */
    public boolean parked() {
        return parked;
    }

    @Nonnull
    public List<String> tags() {
        return tags;
    }
}
