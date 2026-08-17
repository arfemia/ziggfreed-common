package com.ziggfreed.common.quest.event;

import java.util.List;
import java.util.UUID;

import javax.annotation.Nonnull;

import com.hypixel.hytale.event.IEvent;

/**
 * A quest was pinned to, or unpinned from, a player's tracker.
 *
 * <p>Synchronous {@code IEvent<Void>} POJO on the shared engine event bus; see {@link QuestEvents}
 * for the fire contract. Fires only for a REAL change: a pin that was already there and is merely
 * re-stamped fires nothing, and neither does a pin refused at the cap. {@code tracked} is true for
 * a pin and false for an unpin, whether the unpin was the player's own or a stale one the engine
 * swept away because the quest stopped being carried.
 */
public final class QuestTrackedEvent implements IEvent<Void> {

    private final String questId;
    private final UUID playerId;
    private final boolean tracked;
    private final List<String> tags;

    public QuestTrackedEvent(@Nonnull String questId, @Nonnull UUID playerId, boolean tracked,
                             @Nonnull List<String> tags) {
        this.questId = questId;
        this.playerId = playerId;
        this.tracked = tracked;
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

    /** True when the quest was pinned, false when it was unpinned. */
    public boolean tracked() {
        return tracked;
    }

    @Nonnull
    public List<String> tags() {
        return tags;
    }
}
