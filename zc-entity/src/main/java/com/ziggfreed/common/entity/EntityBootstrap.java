package com.ziggfreed.common.entity;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerReadyEvent;
import com.hypixel.hytale.server.core.plugin.PluginBase;
import com.ziggfreed.common.entity.flair.ZigFlairComponent;
import com.ziggfreed.common.entity.performer.PerformerIdentityComponent;
import com.ziggfreed.common.stats.EquipStatBridge;
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

    /** The one bridge, installed here so a stamped stat is real on any server running the library. */
    private static volatile EquipStatBridge equipStatBridge;

    private EntityBootstrap() {
    }

    /**
     * Install the ONE {@link EquipStatBridge}, which is what makes a stamped stat mean anything: it
     * turns a held / worn / offhand stack's stored entries into real modifiers on the entity, and
     * takes them off again when the item comes off.
     *
     * <p>It is the library's rather than a consumer's for the same reason the stamper is. A server
     * running the library and a station mod, with no other consumer at all, still stamps items - and
     * a stamp that never becomes a stat would be a number written on an item that does nothing,
     * which is worse than not offering it. One installer also means one set of modifier keys: two
     * would each apply the same stack's entries under their own namespace and double every bonus.
     *
     * <p>A consumer reaches the installed bridge through {@link #equipStatBridge()} to hang its own
     * post-apply work; it must not install a second one, and must not register its own copies of
     * the three trigger systems below.
     */
    public static void installEquipStatBridge(@Nonnull PluginBase plugin) {
        try {
            EquipStatBridge bridge = EquipStatBridge.install("ziggfreedcommon");
            equipStatBridge = bridge;
            // The ECS system registry is class-keyed, so the three abstract trigger bases are
            // registered as concrete subclasses here, once, by the library that owns the bridge.
            // A consumer must NOT register its own: a second set would be a second recompute of
            // the same stacks. Active-slot switch, held/armor content change, and offhand content
            // change are the three moments an item can start or stop being worn.
            plugin.getEntityStoreRegistry().registerSystem(
                    new EquipStatBridge.ActiveSlotTrigger(bridge) {});
            plugin.getEntityStoreRegistry().registerSystem(
                    new EquipStatBridge.ContentChangeTrigger(bridge) {});
            plugin.getEntityStoreRegistry().registerSystem(
                    new EquipStatBridge.UtilityContentChangeTrigger(bridge) {});
        } catch (Throwable t) {
            SafeLog.warn("[stats] could not install EquipStatBridge", t);
        }
    }

    /** The installed bridge, or null when installation failed. Never install another. */
    @javax.annotation.Nullable
    public static EquipStatBridge equipStatBridge() {
        return equipStatBridge;
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
