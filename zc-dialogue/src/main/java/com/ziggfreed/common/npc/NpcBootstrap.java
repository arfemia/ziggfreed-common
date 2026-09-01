package com.ziggfreed.common.npc;

import java.util.UUID;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerReadyEvent;
import com.hypixel.hytale.server.core.plugin.PluginBase;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.events.AddWorldEvent;
import com.hypixel.hytale.server.core.universe.world.events.RemoveWorldEvent;
import com.ziggfreed.common.cast.WorldEvictors;
import com.ziggfreed.common.npc.placement.asset.NpcPlacementConfig;
import com.ziggfreed.common.npc.placement.runtime.NpcPlacementLedger;
import com.ziggfreed.common.npc.placement.asset.NpcPlacementOverrides;
import com.ziggfreed.common.npc.placement.runtime.NpcPlacementReconciler;
import com.ziggfreed.common.npc.placement.runtime.PlacedNpcComponent;
import com.ziggfreed.common.npc.placement.anchor.PlacementMarkerSystem;
import com.ziggfreed.common.npc.placement.interact.PlacementNpcActions;
import com.ziggfreed.common.npc.placement.command.ZigNpcCommand;
import com.ziggfreed.common.util.SafeLog;

/**
 * Wires the NPC engines this module owns at plugin {@code setup()}: the placement engine, the
 * talk-credit engine, and the world lifecycle listeners that keep both (and every world-keyed
 * cache in the library) honest as worlds come and go. Three ordered phases, each called once from
 * {@code ZiggfreedCommonPlugin.setup()}, which stays the one authority on call ORDER.
 *
 * <p>The NPC action Types here ({@code PlacementNpcActions}, the talk-credit action) register
 * inside {@code setup()} and BEFORE any asset decode: a role asset naming a {@code Type} nothing
 * has registered yet silently fails to parse.
 */
public final class NpcBootstrap {

    private NpcBootstrap() {
    }

    /**
     * Wire the NPC placement engine. The component and the press-F action must both be registered
     * before any asset decode: a role naming an unregistered action type silently fails to parse,
     * and a component registered after a world loads cannot be read off entities that were saved
     * carrying it.
     *
     * <p>The last registration is the placement content's CROSS-ASSET audit, on the first player
     * ready. Those checks ask another store, an open registry or the engine's loaded assets whether
     * an id exists, and only by then have every store folded and every mod's {@code setup()} run -
     * asked at fold time they report whatever had not loaded yet. The audit runs once per boot and
     * stands down where a consumer claimed it, both decided by
     * {@link NpcPlacementConfig#runLateAudit()}.
     */
    public static void setupPlacementEngine(@Nonnull PluginBase plugin) {
        try {
            PlacedNpcComponent.register(plugin.getEntityStoreRegistry());
            PlacementNpcActions.register();
            plugin.getEntityStoreRegistry().registerSystem(new PlacementMarkerSystem());
            NpcPlacementOverrides.getInstance().load();
            plugin.getCommandRegistry().registerCommand(new ZigNpcCommand());
            NpcPlacementLedger.getInstance().load();
            plugin.getEventRegistry().registerGlobal(PlayerReadyEvent.class,
                    event -> NpcPlacementConfig.getInstance().runLateAudit());
        } catch (Throwable t) {
            SafeLog.warn("[placement] engine setup failed", t);
        }
    }

    /**
     * Wire the talk-credit engine: register the {@code ZigTalkCredit} NPC action, join a
     * conversation's {@code MarkTalked} beat to it, and drop a departing player's re-trigger windows.
     *
     * <p>All three belong to common rather than to a consumer. The dialogue engine deliberately stops
     * at resolving WHO a beat is about, so something has to say what crediting that character means;
     * the NPC action needs nothing from any consumer, unlike {@code ZigOpenDialogue}, which cannot
     * work without one; and the re-trigger window is in memory, so a player who leaves and returns
     * should not find their next conversation swallowed by the last one.
     *
     * <p>The action registration runs here, in {@code setup()}, because a role asset naming a
     * {@code Type} nothing has registered yet silently fails to parse.
     */
    public static void setupTalkCredit(@Nonnull PluginBase plugin) {
        try {
            NpcActions.registerTalkCredit();
            NpcTalkDialogue.install();
            plugin.getEventRegistry().register(PlayerDisconnectEvent.class, event -> {
                PlayerRef playerRef = event.getPlayerRef();
                UUID uuid = playerRef == null ? null : playerRef.getUuid();
                if (uuid != null) {
                    TalkCredits.clearPlayer(uuid);
                }
            });
        } catch (Throwable t) {
            SafeLog.warn("[talk] could not wire the talk-credit engine", t);
        }
    }

    /**
     * Drive world eviction from common itself, and sweep a world for missing placements as it is
     * added.
     *
     * <p>The library owns its own {@code AddWorldEvent}/{@code RemoveWorldEvent} listeners because
     * world eviction used to be driven only from CONSUMER listeners, so with two consumer mods
     * installed the {@link WorldEvictors} fan-out fired twice per world. That is harmless for an
     * evictor that removes a map entry and corrupting for one that maintains a reference count,
     * which the placement chunk-pin bookkeeping does. Common driving it itself, plus the
     * idempotence guard in {@link WorldEvictors}, makes the count right however many consumers are
     * installed. The pair lives in this module because the same two listeners also drive the
     * placement sweep, and only this module sees both ends.
     */
    public static void registerWorldLifecycle(@Nonnull PluginBase plugin) {
        try {
            plugin.getEventRegistry().registerGlobal(AddWorldEvent.class, event -> {
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
            plugin.getEventRegistry().registerGlobal(RemoveWorldEvent.class, event -> {
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
}
