package com.ziggfreed.common.reputation;

import java.util.UUID;

import javax.annotation.Nonnull;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerReadyEvent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.util.SafeLog;

/**
 * Where the rank checks come from besides a change: once at login (a silent hydrate, run on a later world
 * task once the player is in), and the transient memory dropped at disconnect. Worn gear adds no standing,
 * so nothing is checked on an equip: a rank a write outside zc moved (the admin {@code /reputation}) heals
 * at the player's next change or login.
 */
public final class ReputationRankTriggers {

    private ReputationRankTriggers() {
    }

    /** Login: check every reputation once the player is in, as a hydrate. */
    public static void onPlayerReady(@Nonnull PlayerReadyEvent event) {
        try {
            Player player = event.getPlayer();
            Ref<EntityStore> ref = event.getPlayerRef();
            World world = player == null ? null : player.getWorld();
            if (world == null || ref == null) {
                return;
            }
            world.execute(() -> check(ref, ReputationRankWatch.Trigger.LOGIN));
        } catch (Throwable t) {
            SafeLog.warn("[reputation] the login rank check could not be queued: " + t.getMessage());
        }
    }

    /** Disconnect: forget the player's last-seen ranks. */
    public static void onPlayerDisconnect(@Nonnull PlayerDisconnectEvent event) {
        try {
            PlayerRef playerRef = event.getPlayerRef();
            UUID uuid = playerRef == null ? null : playerRef.getUuid();
            if (uuid != null) {
                ReputationRuntime.service().forget(uuid);
            }
        } catch (Throwable t) {
            SafeLog.warn("[reputation] forgetting a leaving player's ranks failed: " + t.getMessage());
        }
    }

    private static void check(@Nonnull Ref<EntityStore> ref, @Nonnull ReputationRankWatch.Trigger trigger) {
        if (ref.isValid()) {
            ReputationRuntime.service().checkAll(ref.getStore(), ref, trigger);
        }
    }
}
