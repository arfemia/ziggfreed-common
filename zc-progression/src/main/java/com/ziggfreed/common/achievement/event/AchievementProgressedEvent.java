package com.ziggfreed.common.achievement.event;

import java.util.List;
import java.util.UUID;

import javax.annotation.Nonnull;

import com.hypixel.hytale.event.IEvent;

/**
 * One criterion of an achievement moved.
 *
 * <p>Synchronous {@code IEvent<Void>} POJO on the shared engine event bus; see
 * {@link AchievementEvents} for the fire contract. This is the highest-frequency event of the set -
 * criteria are always on, so it fires on ordinary play - and a listener doing real work should hand
 * off rather than block.
 *
 * <p>The criterion is identified by its ID (the authored {@code Criteria} key), which is what its
 * progress is stored under - a stable name a listener can match on across content edits.
 *
 * <p>{@code justCompleted} distinguishes the tick that FINISHED the criterion from the ones before
 * it, which is what a listener needs to fire a one-time reaction without re-checking the counts.
 */
public final class AchievementProgressedEvent implements IEvent<Void> {

    private final String achievementId;
    private final String criterionId;
    private final UUID playerId;
    private final int current;
    private final int required;
    private final boolean justCompleted;
    private final List<String> tags;

    public AchievementProgressedEvent(@Nonnull String achievementId, @Nonnull String criterionId,
                                      @Nonnull UUID playerId, int current, int required,
                                      boolean justCompleted, @Nonnull List<String> tags) {
        this.achievementId = achievementId;
        this.criterionId = criterionId;
        this.playerId = playerId;
        this.current = current;
        this.required = required;
        this.justCompleted = justCompleted;
        this.tags = List.copyOf(tags);
    }

    @Nonnull
    public String achievementId() {
        return achievementId;
    }

    /** Which criterion moved, by its id (its authored {@code Criteria} key). */
    @Nonnull
    public String criterionId() {
        return criterionId;
    }

    @Nonnull
    public UUID playerId() {
        return playerId;
    }

    /** Progress after this advance. */
    public int current() {
        return current;
    }

    /** What the criterion needs in total. */
    public int required() {
        return required;
    }

    /** True only on the advance that finished the criterion. */
    public boolean justCompleted() {
        return justCompleted;
    }

    @Nonnull
    public List<String> tags() {
        return tags;
    }
}
