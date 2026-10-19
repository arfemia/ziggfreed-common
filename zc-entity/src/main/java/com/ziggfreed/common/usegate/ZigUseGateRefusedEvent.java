package com.ziggfreed.common.usegate;

import javax.annotation.Nonnull;

import com.hypixel.hytale.event.IEvent;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.universe.PlayerRef;

/**
 * A use gate refused a player: a block they were breaking stayed, a hit they dealt did nothing, or a piece of
 * armor stayed out of its slot. The library's own listener tells the player the event's {@link #line()}; another
 * mod listens to react as well (a sound, a counter). It carries what was refused and why, never a way to undo it.
 *
 * <p>Synchronous {@code IEvent<Void>} POJO on the shared engine event bus, fired on the player's world thread
 * inside the refused moment, only while something listens. A refused swing repeats at the swing rate, so a
 * listener keeps its work small.
 */
public final class ZigUseGateRefusedEvent implements IEvent<Void> {

    @Nonnull private final PlayerRef playerRef;
    @Nonnull private final UseGateRefusal refusal;

    public ZigUseGateRefusedEvent(@Nonnull PlayerRef playerRef, @Nonnull UseGateRefusal refusal) {
        this.playerRef = playerRef;
        this.refusal = refusal;
    }

    /** The refused player. */
    @Nonnull
    public PlayerRef playerRef() {
        return playerRef;
    }

    /** The refusing gate's id. */
    @Nonnull
    public String gateId() {
        return refusal.gateId();
    }

    /** The refusing gate's group, folded ({@code ziglevel} for an item's own {@code ZigLevel} tag). */
    @Nonnull
    public String group() {
        return refusal.group();
    }

    /** What the gate was about at this moment. */
    @Nonnull
    public UseGateAxis axis() {
        return refusal.axis();
    }

    /** The line the player is told: the gate's most specific unmet requirement. */
    @Nonnull
    public Message line() {
        return refusal.line();
    }
}
