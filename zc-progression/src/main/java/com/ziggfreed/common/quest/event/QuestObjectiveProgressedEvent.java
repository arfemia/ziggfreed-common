package com.ziggfreed.common.quest.event;

import java.util.List;
import java.util.UUID;

import javax.annotation.Nonnull;

import com.hypixel.hytale.event.IEvent;

/**
 * One objective on an active quest moved.
 *
 * <p>Synchronous {@code IEvent<Void>} POJO on the shared engine event bus; see {@link QuestEvents}
 * for the fire contract. This is the highest-frequency event of the set - it fires per objective per
 * qualifying action - so a listener doing real work should hand off rather than block.
 *
 * <p>{@code justCompleted} distinguishes the tick that FINISHED the objective from the ones before
 * it, which is what a listener needs to fire a one-time reaction without re-checking the counts.
 */
public final class QuestObjectiveProgressedEvent implements IEvent<Void> {

    private final String questId;
    private final String objectiveId;
    private final UUID playerId;
    private final int current;
    private final int required;
    private final boolean justCompleted;
    private final List<String> tags;

    public QuestObjectiveProgressedEvent(@Nonnull String questId, @Nonnull String objectiveId,
                                         @Nonnull UUID playerId, int current, int required,
                                         boolean justCompleted, @Nonnull List<String> tags) {
        this.questId = questId;
        this.objectiveId = objectiveId;
        this.playerId = playerId;
        this.current = current;
        this.required = required;
        this.justCompleted = justCompleted;
        this.tags = List.copyOf(tags);
    }

    @Nonnull
    public String questId() {
        return questId;
    }

    @Nonnull
    public String objectiveId() {
        return objectiveId;
    }

    @Nonnull
    public UUID playerId() {
        return playerId;
    }

    /** Progress after this advance. */
    public int current() {
        return current;
    }

    /** What the objective needs in total. */
    public int required() {
        return required;
    }

    /** True only on the advance that finished the objective. */
    public boolean justCompleted() {
        return justCompleted;
    }

    @Nonnull
    public List<String> tags() {
        return tags;
    }
}
