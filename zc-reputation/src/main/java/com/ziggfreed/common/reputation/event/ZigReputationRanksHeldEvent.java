package com.ziggfreed.common.reputation.event;

import java.util.List;
import java.util.UUID;

import javax.annotation.Nonnull;

import com.hypixel.hytale.event.IEvent;

/**
 * A rank check credited a player these ranks with a reputation, by effective standing (earned plus gear),
 * bottom first: the ranks from the reputation's starting rank up to the current one (below the start, only
 * the ranks reached going down). Fired at login, after a change, and after an equip that moved the rank. Fired on that
 * player's world thread. zc-objectives turns each rank into a {@code REPUTATION_RANK} moment; a listener
 * must expect repeats, since the same ranks are re-stated at every login.
 */
public final class ZigReputationRanksHeldEvent implements IEvent<Void> {

    private final UUID playerId;
    private final String reputationId;
    private final List<String> ranks;

    public ZigReputationRanksHeldEvent(@Nonnull UUID playerId, @Nonnull String reputationId,
            @Nonnull List<String> ranks) {
        this.playerId = playerId;
        this.reputationId = reputationId;
        this.ranks = List.copyOf(ranks);
    }

    @Nonnull
    public UUID playerId() {
        return playerId;
    }

    /** The reputation, in the engine's own spelling of its group id. */
    @Nonnull
    public String reputationId() {
        return reputationId;
    }

    /** The rank ids held, bottom first. */
    @Nonnull
    public List<String> ranks() {
        return ranks;
    }
}
