package com.ziggfreed.common.quest.event;

import java.util.List;
import java.util.UUID;

import javax.annotation.Nonnull;

import com.hypixel.hytale.event.IEvent;

/**
 * A player has taken on a quest.
 *
 * <p>Synchronous {@code IEvent<Void>} POJO on the shared engine event bus. See
 * {@link QuestEvents} for the fire contract (dispatched on the firing thread,
 * skipped entirely when nothing is listening).
 *
 * <p>{@code tags} are whatever the quest was classified with; the engine never interprets them, so a
 * listener can group by its own vocabulary without the engine needing to know that vocabulary.
 */
public final class QuestAcceptedEvent implements IEvent<Void> {

    private final String questId;
    private final UUID playerId;
    private final List<String> tags;

    public QuestAcceptedEvent(@Nonnull String questId, @Nonnull UUID playerId, @Nonnull List<String> tags) {
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
