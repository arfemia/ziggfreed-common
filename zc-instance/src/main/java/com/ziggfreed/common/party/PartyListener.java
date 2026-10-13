package com.ziggfreed.common.party;

import javax.annotation.Nonnull;

/**
 * A consumer hook a UI page (or HUD) registers on a {@link PartyService} to re-render
 * when a party it cares about changes. The listener filters by {@link PartySnapshot#id()}
 * for its own party. zc's own {@code PartyInvitePage} registers one per service itself, so
 * a consumer wires nothing for an open party page to repaint.
 *
 * <p>Every mutation fires one change: an invite sent, accepted, declined or expired, a
 * kick, a leave, a disband, a privacy change and an ownership transfer. A leave that
 * empties the party fires too, its snapshot naming no member; a disband's snapshot still
 * names every member the party held. Creating a solo party ({@code getOrCreate}) alone
 * fires nothing.
 *
 * <p>Same packet-only contract as the lobby's {@code QueueListener}: a callback runs
 * synchronously under the service's lock on the mutating thread (a world thread, a
 * command thread or the invite-expiry scheduler), so it must be cheap, never block, never
 * open a page and never touch a Store/Ref off the world thread. The service guards each
 * callback in a try/catch.
 */
@FunctionalInterface
public interface PartyListener {

    /** The party {@code snapshot.id()} changed; re-render if it is the one being viewed. */
    void onChange(@Nonnull PartySnapshot snapshot);
}
