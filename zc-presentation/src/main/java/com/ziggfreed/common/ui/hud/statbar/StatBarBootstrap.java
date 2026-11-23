package com.ziggfreed.common.ui.hud.statbar;

import javax.annotation.Nonnull;

import com.hypixel.hytale.event.EventPriority;
import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerReadyEvent;
import com.hypixel.hytale.server.core.plugin.PluginBase;
import com.ziggfreed.common.owner.OwnerFileBindings;
import com.ziggfreed.common.util.SafeLog;

/**
 * Registers the stat bars ({@link StatBars}): the HUD's attach at every player-ready and its drop at disconnect, the
 * paint pass, and the owner file {@code mods/ziggfreedcommon/stat-bars.json} bound for the Stat Bars screen. The
 * store itself is registered with the other framework stores. Registration only; with no bar file in force every
 * player carries an empty HUD and the paint pushes nothing.
 */
public final class StatBarBootstrap {

    private StatBarBootstrap() {
    }

    /** Call once from plugin {@code setup()}. */
    public static void install(@Nonnull PluginBase plugin) {
        try {
            plugin.getEventRegistry().registerGlobal(EventPriority.LATE, PlayerReadyEvent.class, StatBars::onPlayerReady);
            plugin.getEventRegistry().register(PlayerDisconnectEvent.class, StatBars::onPlayerDisconnect);
            plugin.getEntityStoreRegistry().registerSystem(new StatBarTickSystem());
        } catch (Throwable t) {
            SafeLog.warn("[hud] the stat bars could not be installed; no stat bar will appear this boot", t);
        }
        try {
            OwnerFileBindings.register(StatBarOwnerLayers.binding());
        } catch (Throwable t) {
            SafeLog.warn("[hud] the stat bars' owner file could not be bound, so the Stat Bars screen writes nothing", t);
        }
    }
}
