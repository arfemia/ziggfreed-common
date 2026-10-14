package com.ziggfreed.common.loot.claim;

import java.util.UUID;

import javax.annotation.Nonnull;

import com.hypixel.hytale.event.IEvent;

/**
 * A new Claim is waiting for a player: {@link ClaimStore#award} wrote it, or {@link ClaimStore#awardOrTopUp} wrote
 * or topped it up. Fired once per Claim written and once per top-up (carrying the Claim as it now stands, which a
 * listener tells apart by its id), never for a second write {@code award} refused, a payout diminished to nothing,
 * or one put back because it did not fit. The player may be offline.
 *
 * <p>Dispatched synchronously on the thread that awarded the Claim, inside the store's lock, so a listener
 * that needs the player's world hops there itself and never blocks.
 */
public final class ClaimAddedEvent implements IEvent<Void> {

    @Nonnull private final UUID player;
    @Nonnull private final Claim claim;

    public ClaimAddedEvent(@Nonnull UUID player, @Nonnull Claim claim) {
        this.player = player;
        this.claim = claim;
    }

    /** Whose Claim it is. */
    @Nonnull
    public UUID player() {
        return player;
    }

    /** The Claim as it was written or topped up (its rewards already diminished). */
    @Nonnull
    public Claim claim() {
        return claim;
    }
}
