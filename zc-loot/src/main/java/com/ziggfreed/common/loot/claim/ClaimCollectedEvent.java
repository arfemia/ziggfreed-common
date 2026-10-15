package com.ziggfreed.common.loot.claim;

import java.util.List;
import java.util.UUID;

import javax.annotation.Nonnull;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.event.IEvent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.instance.reward.InstanceReward;

/**
 * A player collected a Claim, in whole or in part: a Claim press (zc-instance's {@code ClaimGrants}) took it and
 * handed {@link #delivered} over, or the player took items of it out of a spoils chest's window into their bag
 * (zc-instance's {@code SpoilsWindow}). Fired once per Claim a press took, after whatever did not fit went back to
 * wait, and never for a press that handed nothing of it over; from a chest's window, once per Claim an item left,
 * as it leaves, an item that comes back and leaves again never announced twice. What it delivered really reached
 * the player (an item into the bag, all of it or none, a command run, a currency paid).
 *
 * <p>Dispatched synchronously on the player's world thread, inside the press or the window's change, so a listener
 * may touch the player through {@link #ref}; one that would re-enter a page or a payout hops to a later task itself.
 */
public final class ClaimCollectedEvent implements IEvent<Void> {

    @Nonnull private final UUID player;
    @Nonnull private final Ref<EntityStore> ref;
    @Nonnull private final Claim claim;
    @Nonnull private final List<InstanceReward> delivered;

    public ClaimCollectedEvent(@Nonnull UUID player, @Nonnull Ref<EntityStore> ref, @Nonnull Claim claim,
            @Nonnull List<InstanceReward> delivered) {
        this.player = player;
        this.ref = ref;
        this.claim = claim;
        this.delivered = List.copyOf(delivered);
    }

    /** Whose Claim it was. */
    @Nonnull
    public UUID player() {
        return player;
    }

    /** The collecting player, in the world they pressed Claim or opened the chest in. */
    @Nonnull
    public Ref<EntityStore> ref() {
        return ref;
    }

    /** The Claim as the press or the window took it, every reward it held. */
    @Nonnull
    public Claim claim() {
        return claim;
    }

    /** What the press handed over of it, or what left the window of it; never empty. */
    @Nonnull
    public List<InstanceReward> delivered() {
        return delivered;
    }
}
