package com.ziggfreed.common.party;

import javax.annotation.Nonnull;

import com.hypixel.hytale.event.IEvent;

/**
 * A party changed: the native face of {@link PartyListener}, carrying exactly the snapshot a listener
 * is handed.
 *
 * <p>Synchronous {@code IEvent<Void>} POJO on the shared engine event bus, fired by every
 * {@link PartyService} (the library's own and any a consumer holds) on every mutation, right after its
 * registered listeners: an invite sent, accepted, declined or expired, a kick, a leave, a disband, a
 * privacy change and an ownership transfer. A leave that empties the party fires too, its snapshot
 * naming no member; a disband's snapshot still names every member the party held. Creating a solo party
 * alone fires nothing. Filter on {@link PartySnapshot#gameId()} for the service and
 * {@link PartySnapshot#id()} for the party. Built only when somebody is listening ({@link PartyEvents}).
 *
 * <p><b>Threading, the listener's own contract.</b> It fires under the service's lock on the mutating
 * thread, which may be a world thread, a command thread or the invite-expiry scheduler: a listener
 * stays cheap and packet-only, never blocks, never opens a page and never touches a Store or Ref off
 * the world thread (hop to the player's world first).
 */
public final class ZigPartyChangedEvent implements IEvent<Void> {

    private final PartySnapshot snapshot;

    public ZigPartyChangedEvent(@Nonnull PartySnapshot snapshot) {
        this.snapshot = snapshot;
    }

    /** The party as it stands after the change. */
    @Nonnull
    public PartySnapshot snapshot() {
        return snapshot;
    }
}
