package com.ziggfreed.common.health;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;
import com.hypixel.hytale.server.core.event.events.player.RemovedPlayerFromWorldEvent;
import com.hypixel.hytale.server.core.plugin.PluginBase;

/**
 * Registers the record of recent hostile damage a heal is credited against ({@link RecentHostileDamage}): the system
 * that fills it after a creature's hit lands, the one that empties it at a player's death, and its forgetting when a
 * player leaves a world or the server. Registration only; a failed system registration throws here, at setup.
 */
public final class HealCreditBootstrap {

    private HealCreditBootstrap() {
    }

    /** Call once from plugin {@code setup()}. */
    public static void install(@Nonnull PluginBase plugin) {
        plugin.getEntityStoreRegistry().registerSystem(new HostileDamageSystems.Taken());
        plugin.getEntityStoreRegistry().registerSystem(new HostileDamageSystems.Died());
        plugin.getEventRegistry().registerGlobal(RemovedPlayerFromWorldEvent.class, RecentHostileDamage::onLeftWorld);
        plugin.getEventRegistry().register(PlayerDisconnectEvent.class, RecentHostileDamage::onDisconnect);
    }
}
