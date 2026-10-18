package com.ziggfreed.common.progress.gate;

import javax.annotation.Nonnull;

import com.hypixel.hytale.event.EventRegistry;
import com.hypixel.hytale.server.core.event.events.BootEvent;
import com.hypixel.hytale.server.core.event.events.permissions.GroupPermissionChangeEvent;
import com.hypixel.hytale.server.core.event.events.permissions.PlayerGroupEvent;
import com.hypixel.hytale.server.core.event.events.permissions.PlayerPermissionChangeEvent;
import com.hypixel.hytale.server.core.event.events.player.AddPlayerToWorldEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;
import com.hypixel.hytale.server.core.plugin.PluginBase;
import com.ziggfreed.common.util.SafeLog;

/**
 * Wires the engine's events into the gate epochs ({@link GateEpochs}) at the library's {@code setup()}: the
 * boot that turns keeping on, a departing player, a player added to a world (every world, so registered
 * globally: the event is keyed by the world's name), and every permission change (each concrete event, since
 * the bus delivers a listener the class it registered for).
 */
public final class GateBootstrap {

    private GateBootstrap() {
    }

    /** Register the epoch listeners. Once, from the wiring root. */
    public static void install(@Nonnull PluginBase plugin) {
        try {
            EventRegistry events = plugin.getEventRegistry();
            events.register(BootEvent.class, GateEpochs::onBoot);
            events.register(PlayerDisconnectEvent.class, GateEpochs::onDisconnect);
            events.registerGlobal(AddPlayerToWorldEvent.class, GateEpochs::onWorldChange);
            events.register(PlayerPermissionChangeEvent.PermissionsAdded.class, GateEpochs::onPlayerPermissions);
            events.register(PlayerPermissionChangeEvent.PermissionsRemoved.class, GateEpochs::onPlayerPermissions);
            events.register(PlayerPermissionChangeEvent.GroupAdded.class, GateEpochs::onPlayerPermissions);
            events.register(PlayerPermissionChangeEvent.GroupRemoved.class, GateEpochs::onPlayerPermissions);
            events.register(PlayerGroupEvent.Added.class, GateEpochs::onPlayerPermissions);
            events.register(PlayerGroupEvent.Removed.class, GateEpochs::onPlayerPermissions);
            events.register(GroupPermissionChangeEvent.Added.class, GateEpochs::onGroupPermissions);
            events.register(GroupPermissionChangeEvent.Removed.class, GateEpochs::onGroupPermissions);
        } catch (Throwable t) {
            SafeLog.warn("[gate] the gate epochs could not be wired, so no gate verdict is kept between asks", t);
        }
    }
}
