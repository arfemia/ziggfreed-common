package com.ziggfreed.common.objectives.marker;

import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.DelayedEntitySystem;
import com.hypixel.hytale.event.EventPriority;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerReadyEvent;
import com.hypixel.hytale.server.core.plugin.PluginBase;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.cast.WorldEvictors;
import com.ziggfreed.common.npc.placement.runtime.PlacedNpcComponent;
import com.ziggfreed.common.objectives.indicator.QuestMarkYield;
import com.ziggfreed.common.progress.runtime.ProgressionRuntime;
import com.ziggfreed.common.quest.event.QuestAbandonedEvent;
import com.ziggfreed.common.quest.event.QuestAcceptedEvent;
import com.ziggfreed.common.quest.event.QuestClaimedEvent;
import com.ziggfreed.common.quest.event.QuestCompletedEvent;
import com.ziggfreed.common.quest.event.QuestObjectiveProgressedEvent;
import com.ziggfreed.common.quest.event.QuestTrackedEvent;
import com.ziggfreed.common.subject.Subject;
import com.ziggfreed.common.util.SafeLog;

/**
 * The library's quest marks: what a player sees about their quests without opening a page, recomputed
 * one viewer at a time and handed to each surface ({@link QuestMarkerListener}): the marks over the
 * placed characters' heads ({@link QuestOverheads}) and anything else registered.
 *
 * <p><b>When.</b> Immediately on the quest engine's six events and at player ready (every login and
 * world change), for the one player named; and on a slow sweep, {@value #SWEEP_SECONDS} seconds by
 * the engine's own delayed system, for every online player, which catches a character that just
 * arrived, a gate that opened and a viewer who walked into range. Two delayed systems: one indexes
 * the placed characters per world, one evaluates each player against that index. No per-tick work.
 *
 * <p><b>Threading.</b> The sweep runs inside its tick with the tick's command buffer; the event-driven
 * refresh hops to the player's world thread and hands the live store. Reads never leave the world
 * thread.
 */
public final class QuestMarkers {

    /** How often every online player's marks are recomputed at every character in their world. */
    public static final float SWEEP_SECONDS = 5f;

    /** The placed characters standing in each world, indexed by the host system on its cadence. */
    private static final ConcurrentHashMap<World, Set<Ref<EntityStore>>> HOSTS = new ConcurrentHashMap<>();

    private static final CopyOnWriteArrayList<QuestMarkerListener> LISTENERS = new CopyOnWriteArrayList<>();
    private static final Set<String> LISTENER_IDS = ConcurrentHashMap.newKeySet();

    private QuestMarkers() {
    }

    /** Add a surface under {@code id}; a second add under the same id (any case) is ignored. Call from setup. */
    public static void addListener(@Nonnull String id, @Nonnull QuestMarkerListener listener) {
        if (LISTENER_IDS.add(id.trim().toLowerCase(Locale.ROOT))) {
            LISTENERS.add(listener);
        }
    }

    /**
     * Subscribe the refresh to the six quest events, player ready and disconnect, register the two
     * delayed systems and the per-world eviction, and add the overhead surface. Call once from setup;
     * guarded and loud, since a subscription that failed is a mark that lags the sweep.
     */
    public static void install(@Nonnull PluginBase plugin) {
        try {
            var events = plugin.getEventRegistry();
            events.registerGlobal(QuestTrackedEvent.class, event -> refresh(event.playerId()));
            events.registerGlobal(QuestAcceptedEvent.class, event -> refresh(event.playerId()));
            events.registerGlobal(QuestObjectiveProgressedEvent.class, event -> refresh(event.playerId()));
            events.registerGlobal(QuestCompletedEvent.class, event -> refresh(event.playerId()));
            events.registerGlobal(QuestClaimedEvent.class, event -> refresh(event.playerId()));
            events.registerGlobal(QuestAbandonedEvent.class, event -> refresh(event.playerId()));
            events.registerGlobal(EventPriority.LATE, PlayerReadyEvent.class, QuestMarkers::onPlayerReady);
            events.register(PlayerDisconnectEvent.class, QuestMarkers::onPlayerDisconnect);
            QuestMarkYield.onConsumerDraws(QuestMarkers::refreshAll);
            addListener("overheads", QuestOverheads.INSTANCE);
        } catch (Throwable t) {
            SafeLog.warn("[progression] the quest marks could not subscribe to the quest events", t);
        }
        try {
            ComponentType<EntityStore, PlacedNpcComponent> hostType = PlacedNpcComponent.getComponentType();
            if (hostType == null) {
                SafeLog.warn("[progression] the placement component is not registered, so no character can"
                        + " carry a quest mark this boot");
                return;
            }
            plugin.getEntityStoreRegistry().registerSystem(new HostIndexSystem(hostType));
            plugin.getEntityStoreRegistry().registerSystem(new ViewerSweepSystem());
            WorldEvictors.registerEvictor(HOSTS::remove);
            SafeLog.info("[progression] quest marks installed: over placed characters' heads and on the map,"
                    + " refreshed on the six quest events, at player ready and every " + (int) SWEEP_SECONDS
                    + " s");
        } catch (Throwable t) {
            SafeLog.warn("[progression] the quest-mark sweep could not be registered", t);
        }
    }

    /**
     * Recompute {@code playerId}'s marks from any thread: resolves the player, hops to their world
     * thread and evaluates with the live store. A player offline or between worlds is left to the
     * next sweep.
     */
    public static void refresh(@Nonnull UUID playerId) {
        try {
            PlayerRef playerRef = Universe.get().getPlayer(playerId);
            Ref<EntityStore> ref = playerRef != null ? playerRef.getReference() : null;
            if (ref == null) {
                return;
            }
            World world = ref.getStore().getExternalData().getWorld();
            if (world == null || !world.isAlive()) {
                return;
            }
            world.execute(() -> {
                Ref<EntityStore> live = playerRef.getReference();
                if (live == null || !live.isValid()) {
                    return;
                }
                Store<EntityStore> store = live.getStore();
                evaluate(store, store, world, live, playerId);
            });
        } catch (Throwable t) {
            SafeLog.warn("[progression] a quest-mark refresh could not reach the player: " + t.getMessage());
        }
    }

    /** {@link #refresh} every online player: a consumer has just been seen drawing its own marks. */
    public static void refreshAll() {
        try {
            for (PlayerRef player : Universe.get().getPlayers()) {
                UUID uuid = player == null ? null : player.getUuid();
                if (uuid != null) {
                    refresh(uuid);
                }
            }
        } catch (Throwable t) {
            SafeLog.warn("[progression] the quest marks could not be refreshed for everyone: " + t.getMessage());
        }
    }

    /** One viewer's whole answer, handed to every surface. World thread. */
    static void evaluate(@Nonnull Store<EntityStore> store, @Nonnull ComponentAccessor<EntityStore> accessor,
            @Nonnull World world, @Nonnull Ref<EntityStore> viewerRef, @Nonnull UUID viewerId) {
        Subject subject;
        try {
            subject = ProgressionRuntime.subjects().questSubject(store, viewerRef);
        } catch (Throwable t) {
            SafeLog.warn("[progression] a quest-mark evaluation could not read the player: " + t.getMessage());
            return;
        }
        if (subject == null) {
            return;
        }
        QuestMarkerScope scope = new QuestMarkerScope(store, accessor, world, viewerRef, viewerId, subject,
                hostsIn(world));
        for (QuestMarkerListener listener : LISTENERS) {
            try {
                listener.evaluate(scope);
            } catch (Throwable t) {
                SafeLog.warn("[progression] a quest-mark surface failed: " + t.getMessage());
            }
        }
    }

    /** Tell every surface a viewer left; one that throws costs only itself. Any thread. */
    static void forgetEverywhere(@Nonnull UUID viewerId) {
        for (QuestMarkerListener listener : LISTENERS) {
            try {
                listener.forget(viewerId);
            } catch (Throwable t) {
                SafeLog.warn("[progression] a quest-mark surface failed to forget a player: " + t.getMessage());
            }
        }
    }

    /** The surfaces added. Tests only. */
    static int listenerCount() {
        return LISTENERS.size();
    }

    /** Drop every surface. Tests only. */
    static void resetForTests() {
        LISTENERS.clear();
        LISTENER_IDS.clear();
    }

    /** The live placed characters indexed for {@code world}, pruning any that went away. */
    @Nonnull
    private static Set<Ref<EntityStore>> hostsIn(@Nonnull World world) {
        Set<Ref<EntityStore>> hosts = HOSTS.get(world);
        if (hosts == null) {
            return Set.of();
        }
        hosts.removeIf(host -> !host.isValid());
        return hosts;
    }

    private static void onPlayerReady(@Nonnull PlayerReadyEvent event) {
        try {
            Player player = event.getPlayer();
            World world = player.getWorld();
            if (world == null) {
                return;
            }
            world.execute(() -> {
                Ref<EntityStore> ref = player.getReference();
                if (ref == null || !ref.isValid()) {
                    return;
                }
                Store<EntityStore> store = ref.getStore();
                PlayerRef playerRef = store.getComponent(ref, PlayerRef.getComponentType());
                if (playerRef != null && playerRef.getUuid() != null) {
                    evaluate(store, store, world, ref, playerRef.getUuid());
                }
            });
        } catch (Throwable t) {
            SafeLog.warn("[progression] quest marks failed at player ready", t);
        }
    }

    private static void onPlayerDisconnect(@Nonnull PlayerDisconnectEvent event) {
        PlayerRef playerRef = event.getPlayerRef();
        UUID uuid = playerRef != null ? playerRef.getUuid() : null;
        if (uuid != null) {
            forgetEverywhere(uuid);
        }
    }

    /** Index the placed characters standing in each world, on the sweep cadence; nothing else. */
    static final class HostIndexSystem extends DelayedEntitySystem<EntityStore> {

        @Nonnull private final ComponentType<EntityStore, PlacedNpcComponent> hostType;

        HostIndexSystem(@Nonnull ComponentType<EntityStore, PlacedNpcComponent> hostType) {
            super(SWEEP_SECONDS);
            this.hostType = hostType;
        }

        @Nullable
        @Override
        public Query<EntityStore> getQuery() {
            return hostType;
        }

        @Override
        public void tick(float dt, int index, @Nonnull ArchetypeChunk<EntityStore> archetypeChunk,
                @Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> commandBuffer) {
            Ref<EntityStore> host = archetypeChunk.getReferenceTo(index);
            HOSTS.computeIfAbsent(WorldEvictors.worldOf(store), w -> ConcurrentHashMap.newKeySet()).add(host);
        }
    }

    /** Evaluate every online player against their world's characters, on the sweep cadence. */
    static final class ViewerSweepSystem extends DelayedEntitySystem<EntityStore> {

        ViewerSweepSystem() {
            super(SWEEP_SECONDS);
        }

        @Nullable
        @Override
        public Query<EntityStore> getQuery() {
            return PlayerRef.getComponentType();
        }

        @Override
        public void tick(float dt, int index, @Nonnull ArchetypeChunk<EntityStore> archetypeChunk,
                @Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> commandBuffer) {
            PlayerRef playerRef = archetypeChunk.getComponent(index, PlayerRef.getComponentType());
            UUID viewerId = playerRef == null ? null : playerRef.getUuid();
            if (viewerId == null) {
                return;
            }
            evaluate(store, commandBuffer, WorldEvictors.worldOf(store), archetypeChunk.getReferenceTo(index), viewerId);
        }
    }
}
