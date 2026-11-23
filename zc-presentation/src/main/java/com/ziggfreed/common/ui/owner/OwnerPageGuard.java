package com.ziggfreed.common.ui.owner;

import javax.annotation.Nonnull;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.Page;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import com.ziggfreed.common.owner.OwnerAudience;
import com.ziggfreed.common.util.SafeLog;

/**
 * The one re-check every owner page makes on every event but Close: a page's build only decides what it shows, and
 * a client can send any event, so who may act is asked again each time, of the page's own audience (the owner
 * audience, or a consumer's seam on a library owner page). The owner menu, the owner form and the library's owner
 * pages (the progression switches, the use gates, the NPC placements) all call it.
 *
 * <p>A refusal never leaves the client waiting: the player is told in chat and the page closes, which answers the
 * event. World thread.
 */
public final class OwnerPageGuard {

    private OwnerPageGuard() {
    }

    /**
     * May {@code player} still act on the owner page they sent an event from? True to go on. False when
     * {@code audience} refuses (or throws): the refusal line is said and the page closed, so the caller answers
     * nothing more.
     */
    public static boolean stillAllowed(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
            @Nonnull Player player, @Nonnull PlayerRef playerRef, @Nonnull OwnerAudience.Audience audience) {
        boolean allowed;
        try {
            allowed = audience.mayAdminister(store, ref, player);
        } catch (Throwable t) {
            SafeLog.warn("[owner] an owner page's audience failed, so the event is refused: " + t.getMessage());
            allowed = false;
        }
        if (allowed) {
            return true;
        }
        playerRef.sendMessage(OwnerText.line("menu.refused"));
        player.getPageManager().setPage(ref, store, Page.None);
        return false;
    }
}
