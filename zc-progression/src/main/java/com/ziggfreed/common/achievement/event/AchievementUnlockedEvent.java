package com.ziggfreed.common.achievement.event;

import java.util.List;
import java.util.UUID;

import javax.annotation.Nonnull;

import com.hypixel.hytale.event.IEvent;

/**
 * A subject earned an achievement.
 *
 * <p>Synchronous {@code IEvent<Void>} POJO on the shared engine event bus; see
 * {@link AchievementEvents} for the fire contract.
 *
 * <p>{@code awaitingClaim} says whether anything is still waiting to be collected: false means the
 * whole payout already landed and {@link AchievementClaimedEvent} fired immediately after this one.
 * {@code points} is what this one is worth, so a listener can react to the total moving without
 * asking the engine.
 */
public final class AchievementUnlockedEvent implements IEvent<Void> {

    private final String achievementId;
    private final UUID playerId;
    private final int points;
    private final boolean awaitingClaim;
    private final List<String> tags;

    public AchievementUnlockedEvent(@Nonnull String achievementId, @Nonnull UUID playerId, int points,
                                    boolean awaitingClaim, @Nonnull List<String> tags) {
        this.achievementId = achievementId;
        this.playerId = playerId;
        this.points = points;
        this.awaitingClaim = awaitingClaim;
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

    /** What this achievement is worth toward a points total. */
    public int points() {
        return points;
    }

    /** True when something is still waiting to be collected. */
    public boolean awaitingClaim() {
        return awaitingClaim;
    }

    @Nonnull
    public List<String> tags() {
        return tags;
    }
}
