package com.ziggfreed.common.party.page;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

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

    /**
     * Queue the party led by {@code initiator} for {@code presetId} (the difficulty the
     * player chose upstream on the Play screen; {@code null} when the page was opened
     * standalone, e.g. a {@code /party} command, so the consumer falls back to its default
     * preset). Runs on the world thread (the page's event handler).
     */
    void queue(@Nonnull PlayerRef initiator, @Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store,
               @Nullable String presetId);
}
