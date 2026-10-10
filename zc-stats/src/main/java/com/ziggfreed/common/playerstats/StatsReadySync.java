package com.ziggfreed.common.playerstats;

import javax.annotation.Nonnull;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.event.events.player.PlayerReadyEvent;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.util.SafeLog;

/**
 * The ready pass: once a player is in the world, the stat board's all-time ranking is set from their
 * whole record, so an import, or a count made while the board could not rank it, shows at once.
 * Registered at NORMAL priority and run as a task on the player's world, so a consumer's FIRST-priority
 * ready task (a one-time import into this record) has already run when it reads.
 */
public final class StatsReadySync {

    private StatsReadySync() {
    }

    public static void onPlayerReady(@Nonnull PlayerReadyEvent event) {
        try {
            Player player = event.getPlayer();
            World world = player == null ? null : player.getWorld();
            Ref<EntityStore> ref = event.getPlayerRef();
            if (world == null || ref == null) {
                return;
            }
            world.execute(() -> {
                try {
                    if (ref.isValid()) {
                        PlayerStats.resync(ref.getStore(), ref);
                    }
                } catch (Throwable t) {
                    SafeLog.warn("[stats] the ready re-sync of a player's stats failed", t);
                }
            });
        } catch (Throwable t) {
            SafeLog.warn("[stats] the ready re-sync of a player's stats failed", t);
        }
    }
}
