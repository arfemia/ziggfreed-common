package com.ziggfreed.common.quest.event;

import java.util.List;
import java.util.UUID;

import javax.annotation.Nonnull;

import com.hypixel.hytale.event.IEvent;

/**
 * A player has given up an active quest; its progress is gone and it is offerable again.
 *
 * <p>Synchronous {@code IEvent<Void>} POJO on the shared engine event bus; see {@link QuestEvents}
 * for the fire contract. Fires only for a deliberate abandon, never for a maintenance reset of a
 * finished repeatable.
 */
public final class QuestAbandonedEvent implements IEvent<Void> {

    private final String questId;
    private final UUID playerId;
    private final List<String> tags;

    public QuestAbandonedEvent(@Nonnull String questId, @Nonnull UUID playerId, @Nonnull List<String> tags) {
        this.questId = questId;
        this.playerId = playerId;
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

    @Nonnull
    public List<String> tags() {
        return tags;
    }
}
