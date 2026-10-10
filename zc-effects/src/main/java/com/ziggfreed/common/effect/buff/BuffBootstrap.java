package com.ziggfreed.common.effect.buff;

import javax.annotation.Nonnull;

import com.hypixel.hytale.event.EventPriority;
import com.hypixel.hytale.server.core.event.events.player.PlayerReadyEvent;
import com.hypixel.hytale.server.core.plugin.PluginBase;
import com.ziggfreed.common.util.SafeLog;

/**
 * Registers the timed-buff binding ({@link TimedBuffs}): the per-player slot record with its connect
 * hook, the player-ready refresh and the respawn refresh. Registration only.
 *
 * <p>The record is registered first and unconditionally: a component type registered after a world
 * has loaded cannot be read off entities saved carrying it, so it cannot wait to learn whether a
 * consumer registers a source. Every refresh does nothing until one does.
 */
public final class BuffBootstrap {

    private BuffBootstrap() {
    }

    /** Call once from plugin {@code setup()}. */
    public static void install(@Nonnull PluginBase plugin) {
        try {
            BuffSlotsComponent.register(plugin.getEntityStoreRegistry());
            BuffSlotsComponent.install(plugin);
        } catch (Throwable t) {
            SafeLog.warn("[buff] could not register the buff slot record", t);
        }
        try {
            // LATE, so it queues after every earlier ready listener's world-thread work, a consumer's
            // own load of the state its source reads included.
            plugin.getEventRegistry().registerGlobal(EventPriority.LATE, PlayerReadyEvent.class, TimedBuffs::onPlayerReady);
            plugin.getEntityStoreRegistry().registerSystem(new TimedBuffRespawnSystem());
        } catch (Throwable t) {
            SafeLog.warn("[buff] could not hang the timed-buff refreshes", t);
        }
    }
}
