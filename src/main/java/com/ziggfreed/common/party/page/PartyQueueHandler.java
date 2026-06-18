package com.ziggfreed.common.party.page;

import javax.annotation.Nonnull;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * The consumer's "Queue this party" handoff, invoked when the party-page Queue button is
 * pressed. The consumer (e.g. Kweebec's {@code KweebecParty}) routes the owner's party
 * through the lobby's {@code joinParty} (public or private). Kept a seam so common never
 * imports a mod's lobby wiring; if absent, the Queue button is a no-op.
 */
@FunctionalInterface
public interface PartyQueueHandler {

    /** Queue the party led by {@code initiator}. Runs on the world thread (the page's handler). */
    void queue(@Nonnull PlayerRef initiator, @Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store);
}
