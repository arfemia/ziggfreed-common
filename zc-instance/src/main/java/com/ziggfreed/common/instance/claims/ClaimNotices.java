package com.ziggfreed.common.instance.claims;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nonnull;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerReadyEvent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.inventory.PlayerAccess;
import com.ziggfreed.common.loot.claim.ClaimAddedEvent;
import com.ziggfreed.common.loot.claim.ClaimStore;
import com.ziggfreed.common.ui.toast.ToastDelivery;
import com.ziggfreed.common.ui.toast.ToastKind;
import com.ziggfreed.common.ui.toast.ToastSpec;
import com.ziggfreed.common.util.SafeLog;

/**
 * Telling a player a Claim waits, where they are looking (M527): a new Claim raises a toast naming the tab, drawn
 * into the page they have open or else in the corner, and once a session, at the first ready after login (or
 * after a world hop that comes before any toast), a notice saying how many wait. Neither hands anything over:
 * Claims are only ever collected (M428). A player offline when a Claim arrives hears of it at their next login.
 * Both work on a server running nothing but this library.
 */
public final class ClaimNotices {

    /** The players already told this session that Claims wait, so a world hop does not say it again. */
    private static final Set<UUID> TOLD = ConcurrentHashMap.newKeySet();

    private ClaimNotices() {
    }

    /** A new Claim: the toast, on the player's world, if they are online. */
    public static void onAdded(@Nonnull ClaimAddedEvent event) {
        try {
            Universe universe = Universe.get();
            PlayerRef playerRef = universe == null ? null : universe.getPlayer(event.player());
            if (playerRef == null || playerRef.getReference() == null) {
                return;
            }
            TOLD.add(event.player());
            ToastDelivery.deliverWhenSettled(playerRef, addedToast(ClaimsText.LIBRARY), null);
        } catch (Throwable t) {
            SafeLog.fine("[claims] the new-Claim toast could not be raised: " + t.getMessage());
        }
    }

    /** A player ready in a world: the notice, once a session, while any Claim waits. */
    public static void onPlayerReady(@Nonnull PlayerReadyEvent event) {
        try {
            Player player = event.getPlayer();
            Ref<EntityStore> ref = event.getPlayerRef();
            World world = player == null ? null : player.getWorld();
            if (world == null || ref == null) {
                return;
            }
            world.execute(() -> tellWaiting(ref));
        } catch (Throwable t) {
            SafeLog.fine("[claims] the Claims notice could not be queued: " + t.getMessage());
        }
    }

    /** A player leaving: their next session is told again. */
    public static void onPlayerDisconnect(@Nonnull PlayerDisconnectEvent event) {
        PlayerRef playerRef = event.getPlayerRef();
        UUID player = playerRef == null ? null : playerRef.getUuid();
        if (player != null) {
            TOLD.remove(player);
        }
    }

    /** On the player's world thread: say how many Claims wait, unless none do or this session already heard. */
    private static void tellWaiting(@Nonnull Ref<EntityStore> ref) {
        try {
            if (!ref.isValid()) {
                return;
            }
            PlayerRef playerRef = PlayerAccess.playerRef(ref.getStore(), ref);
            UUID player = playerRef == null ? null : playerRef.getUuid();
            int waiting = player == null ? 0 : ClaimStore.shared().count(player);
            if (waiting <= 0 || !TOLD.add(player)) {
                return;
            }
            ToastDelivery.deliver(playerRef, readyNotice(ClaimsText.LIBRARY, waiting), null);
        } catch (Throwable t) {
            SafeLog.fine("[claims] the Claims notice could not be shown: " + t.getMessage());
        }
    }

    /** What a new Claim says: one is waiting under Claims in the menu. */
    @Nonnull
    static ToastSpec addedToast(@Nonnull ClaimsText text) {
        return ToastSpec.of(ToastKind.REWARD, text.line("notice.added")).withIcon(ClaimsDestinations.ICON);
    }

    /** What the ready notice says: how many wait under Claims in the menu. */
    @Nonnull
    static ToastSpec readyNotice(@Nonnull ClaimsText text, int waiting) {
        return ToastSpec.of(ToastKind.INFO, text.line("notice.ready", waiting)).withIcon(ClaimsDestinations.ICON);
    }
}
