package com.ziggfreed.common;

import javax.annotation.Nonnull;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerReadyEvent;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.events.AddWorldEvent;
import com.hypixel.hytale.server.core.universe.world.events.RemoveWorldEvent;
import com.ziggfreed.common.asset.FrameworkAssetRegistrar;
import com.ziggfreed.common.cast.WorldEvictors;
import com.ziggfreed.common.entity.PlayerIdentityCache;
import com.ziggfreed.common.npc.placement.NpcPlacementLedger;
import com.ziggfreed.common.npc.placement.NpcPlacementOverrides;
import com.ziggfreed.common.npc.placement.NpcPlacementReconciler;
import com.ziggfreed.common.npc.placement.PlacedNpcComponent;
import com.ziggfreed.common.npc.placement.PlacementMarkerSystem;
import com.ziggfreed.common.npc.placement.PlacementNpcActions;
import com.ziggfreed.common.util.SafeLog;

/**
 * Entry point for Ziggfreed Common, a shared, mod-agnostic Hytale utility mod.
 *
 * <p>It ships a set of stateless, config-free primitives ({@code sound/}, {@code camera/},
 * {@code util/}, {@code feedback/}, {@code ui/}) lifted from the MMO Skill Tree mod and the Kweebec
 * Nightmare minigame so that standalone Ziggfreed minigames (and eventually the MMO) can consume
 * one battle-tested implementation instead of re-deriving it per mod, PLUS the reusable
 * instance-experience framework (dialogues, instance presets, leaderboard layout, party settings,
 * multi-phase bosses, banded effects, encounter rules, prefab placements). There is NO MMO config,
 * no per-player component, and no Perfect Utils coupling here - the only dependency is the Hytale
 * server jar.
 *
 * <p>The static primitives register nothing (a consumer calls them directly), but this plugin DOES
 * own four registrations of its own:
 * <ul>
 *   <li>the framework asset stores ({@link FrameworkAssetRegistrar}), so common owns each store at
 *       {@code Server/ZiggfreedCommon/<Type>/} exactly once;</li>
 *   <li>the NPC placement engine's component, marker system and press-F action, because that
 *       engine is common's own and no consumer can be asked to register another mod's pieces;</li>
 *   <li><b>its own {@code AddWorldEvent}/{@code RemoveWorldEvent} listeners.</b> World eviction
 *       used to be driven only from CONSUMER listeners, so with two consumer mods installed the
 *       fan-out fired twice per world. That is harmless for an evictor that removes a map entry
 *       and corrupting for one that maintains a reference count, which the placement chunk-pin
 *       bookkeeping does. Common driving it itself, plus the idempotence guard in
 *       {@link WorldEvictors}, makes the count right however many consumers are installed.</li>
 *   <li><b>the {@link PlayerIdentityCache} lifecycle listeners.</b> The cache is common's own
 *       primitive and the only supported way to identify a player off the world thread, so it has
 *       to be kept current here rather than by whichever consumer happens to read it.</li>
 * </ul>
 */
public class ZiggfreedCommonPlugin extends JavaPlugin {

    /**
     * The library's logger, kept here as a convenience for anything already holding the plugin
     * class. It is the very same handle as {@link CommonLog#LOGGER} (same name, same backend), so
     * either route prints identical lines; prefer {@code CommonLog.LOGGER} in library code, which
     * needs no reference to the plugin entry point at all.
     */
    public static final HytaleLogger LOGGER = CommonLog.LOGGER;

    private static ZiggfreedCommonPlugin instance;

    @Nonnull
    public static ZiggfreedCommonPlugin getInstance() {
        return instance;
    }

    public ZiggfreedCommonPlugin(@Nonnull JavaPluginInit init) {
        super(init);
        instance = this;
        LOGGER.atInfo().log("ZiggfreedCommon initializing...");
    }

    @Override
    protected void setup() {
        // Register the framework asset stores ONCE (common owns them at
        // Server/ZiggfreedCommon/<Type>/). The stateless static primitives still register
        // nothing - a consumer calls them directly.
        FrameworkAssetRegistrar.registerAll(this);

        setupPlacementEngine();
        registerWorldLifecycle();
        registerPlayerIdentity();

        LOGGER.atInfo().log("ZiggfreedCommon setup complete (framework stores + shared primitives available).");
    }

    /**
     * Wire the NPC placement engine. The component and the press-F action must both be registered
     * before any asset decode: a role naming an unregistered action type silently fails to parse,
     * and a component registered after a world loads cannot be read off entities that were saved
     * carrying it.
     */
    private void setupPlacementEngine() {
        try {
            PlacedNpcComponent.register(getEntityStoreRegistry());
            PlacementNpcActions.register();
            getEntityStoreRegistry().registerSystem(new PlacementMarkerSystem());
            NpcPlacementOverrides.getInstance().load();
            NpcPlacementLedger.getInstance().load();
        } catch (Throwable t) {
            SafeLog.warn("[placement] engine setup failed", t);
        }
    }

    /**
     * Drive world eviction from common itself (see the class javadoc for why), and sweep a world
     * for missing placements as it is added.
     */
    private void registerWorldLifecycle() {
        try {
            getEventRegistry().registerGlobal(AddWorldEvent.class, event -> {
                try {
                    if (event.isCancelled()) {
                        return;
                    }
                    World added = event.getWorld();
                    if (added == null) {
                        return;
                    }
                    WorldEvictors.onWorldAdded(added);
                    NpcPlacementReconciler.clearDebounce(added);
                    NpcPlacementReconciler.requestSweep(added, added.getEntityStore().getStore());
                } catch (Throwable t) {
                    SafeLog.warn("[placement] world-add handling failed: " + t.getMessage());
                }
            });
            getEventRegistry().registerGlobal(RemoveWorldEvent.class, event -> {
                try {
                    if (event.isCancelled()) {
                        return;
                    }
                    World removed = event.getWorld();
                    if (removed != null) {
                        WorldEvictors.onWorldRemoved(removed);
                    }
                } catch (Throwable t) {
                    SafeLog.warn("[placement] world-removal teardown failed: " + t.getMessage());
                }
            });
        } catch (Throwable t) {
            SafeLog.warn("[placement] could not register the world lifecycle listeners", t);
        }
    }

    /**
     * Keep {@link PlayerIdentityCache} current. Common owns these two listeners for the same reason
     * it owns the world-lifecycle pair above: the cache is common's own primitive, several
     * consumers read it off the world thread, and no consumer can be asked to register another
     * mod's plumbing (nor should two installed consumers each register their own copy).
     */
    private void registerPlayerIdentity() {
        try {
            getEventRegistry().registerGlobal(PlayerReadyEvent.class, PlayerIdentityCache::onPlayerReady);
            getEventRegistry().register(PlayerDisconnectEvent.class, PlayerIdentityCache::onPlayerDisconnect);
        } catch (Throwable t) {
            SafeLog.warn("[identity] could not register the player-identity listeners", t);
        }
    }

    @Override
    protected void shutdown() {
        LOGGER.atInfo().log("ZiggfreedCommon shutdown complete.");
    }
}
