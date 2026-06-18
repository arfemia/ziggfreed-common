package com.ziggfreed.common.party;

import javax.annotation.Nonnull;

/**
 * A consumer hook a UI page (or HUD) registers on a {@link PartyService} to re-render
 * when a party it cares about changes (an invite accepted, a member left, the owner
 * transferred). The listener filters by {@link PartySnapshot#id()} for its own party.
 *
 * <p>Same packet-only contract as the lobby's {@code QueueListener}: callbacks must be
 * cheap and never touch a Store/Ref off the world thread. The service guards each
 * callback in a try/catch.
 */
@FunctionalInterface
public interface PartyListener {

    /** The party {@code snapshot.id()} changed; re-render if it is the one being viewed. */
    void onChange(@Nonnull PartySnapshot snapshot);
}
