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
 * <p>The criterion is identified by its INDEX, which is what its progress is stored under. Reordering
 * an achievement's criteria therefore changes what an index refers to, for a listener exactly as it
 * does for the store.
 *
 * <p>{@code justCompleted} distinguishes the tick that FINISHED the criterion from the ones before
 * it, which is what a listener needs to fire a one-time reaction without re-checking the counts.
 */
public final class AchievementProgressedEvent implements IEvent<Void> {

    private final String achievementId;
    private final int criterionIndex;
    private final UUID playerId;
    private final int current;
    private final int required;
    private final boolean justCompleted;
    private final List<String> tags;

    public AchievementProgressedEvent(@Nonnull String achievementId, int criterionIndex,
                                      @Nonnull UUID playerId, int current, int required,
                                      boolean justCompleted, @Nonnull List<String> tags) {
        this.achievementId = achievementId;
        this.criterionIndex = criterionIndex;
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

    /** Which criterion moved, by its position in the achievement's list. */
    public int criterionIndex() {
        return criterionIndex;
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
