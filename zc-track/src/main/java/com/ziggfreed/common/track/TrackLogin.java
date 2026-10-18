package com.ziggfreed.common.track;

import javax.annotation.Nonnull;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.event.events.player.PlayerReadyEvent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.util.SafeLog;

/**
 * A player's settle once they are ready in a world (a login and every world change): on a later world task, so
 * the player stands in the store, every usable track's level is written onto its mirror stat and every unlock
 * Claim owed and not yet parked is parked ({@link TrackService#settle}).
 */
public final class TrackLogin {

    private TrackLogin() {
    }

    /** Queue the settle on the player's world thread. */
    public static void onPlayerReady(@Nonnull PlayerReadyEvent event) {
        try {
            Player player = event.getPlayer();
            Ref<EntityStore> ref = event.getPlayerRef();
            World world = player == null ? null : player.getWorld();
            if (world == null || ref == null) {
                return;
            }
            world.execute(() -> settle(ref));
        } catch (Throwable t) {
            SafeLog.warn("[track] the login settle could not be queued: " + t.getMessage());
        }
    }

    private static void settle(@Nonnull Ref<EntityStore> ref) {
        try {
            if (!ref.isValid()) {
                return;
            }
            PlayerRef player = ref.getStore().getComponent(ref, PlayerRef.getComponentType());
            if (player != null) {
                TrackService.settle(player.getUuid());
            }
        } catch (Throwable t) {
            SafeLog.warn("[track] the login settle failed: " + t.getMessage());
        }
    }
}
