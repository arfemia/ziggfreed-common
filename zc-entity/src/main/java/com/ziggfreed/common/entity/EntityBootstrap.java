package com.ziggfreed.common.entity;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerReadyEvent;
import com.hypixel.hytale.server.core.plugin.PluginBase;
import com.ziggfreed.common.entity.flair.ZigFlairComponent;
import com.ziggfreed.common.entity.performer.PerformerIdentityComponent;
import com.ziggfreed.common.util.SafeLog;

/**
 * Registers this module's own per-player state and plumbing at plugin {@code setup()}: the
 * station-performer identity component, the unlocked-flair set with its connect hook, and the
 * {@link PlayerIdentityCache} lifecycle listeners. Three ordered phases, each called once from
 * {@code ZiggfreedCommonPlugin.setup()}, which stays the one authority on call ORDER.
 *
 * <p>Every component type here is registered unconditionally and early: a component type
 * registered after a world has loaded cannot be read off entities that were saved carrying it, so
 * none of these can wait to find out whether a consumer brings its own store.
 */
public final class EntityBootstrap {

    private EntityBootstrap() {
    }

    /**
     * Register the station-performer identity component ({@link PerformerIdentityComponent}), so a
     * server running any performer-driven consumer (RPG Stations today) gets working orphan-reconcile
     * without that consumer having to remember to register another library's component itself. A
     * component registered after a world has loaded cannot be read off entities that were saved
     * carrying it, so this cannot wait for a consumer's own {@code setup()} to decide whether it will.
     */
    public static void registerPerformerIdentity(@Nonnull PluginBase plugin) {
        try {
            PerformerIdentityComponent.register(plugin.getEntityStoreRegistry());
        } catch (Throwable t) {
            SafeLog.warn("[performer] could not register PerformerIdentityComponent", t);
        }
    }

    /**
     * Register the per-player unlocked-flair set ({@link ZigFlairComponent}) and hang its connect
     * hook, so what a player unlocked is remembered by the library whoever grants it and whoever
     * renders it - the same reasoning as the progress component: per-player state two different
     * mods meet over belongs to the library both already load.
     */
    public static void registerFlairs(@Nonnull PluginBase plugin) {
        try {
            ZigFlairComponent.register(plugin.getEntityStoreRegistry());
            ZigFlairComponent.install(plugin);
        } catch (Throwable t) {
            SafeLog.warn("[flair] could not register ZigFlairComponent", t);
        }
    }

    /**
     * Keep {@link PlayerIdentityCache} current. The library owns these two listeners for the same
     * reason it owns the world-lifecycle pair ({@code NpcBootstrap.registerWorldLifecycle}): the
     * cache is common's own primitive, several consumers read it off the world thread, and no
     * consumer can be asked to register another mod's plumbing (nor should two installed consumers
     * each register their own copy).
     */
    public static void registerPlayerIdentity(@Nonnull PluginBase plugin) {
        try {
            plugin.getEventRegistry().registerGlobal(PlayerReadyEvent.class, PlayerIdentityCache::onPlayerReady);
            plugin.getEventRegistry().register(PlayerDisconnectEvent.class, PlayerIdentityCache::onPlayerDisconnect);
        } catch (Throwable t) {
            SafeLog.warn("[identity] could not register the player-identity listeners", t);
        }
    }
}
