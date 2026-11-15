package com.ziggfreed.common.objectives.producer;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.protocol.MovementStates;
import com.hypixel.hytale.server.core.entity.movement.MovementStatesComponent;
import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;
import com.hypixel.hytale.server.core.modules.physics.component.Velocity;
import com.hypixel.hytale.server.core.plugin.PluginBase;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.inventory.PlayerAccess;
import com.ziggfreed.common.util.SafeLog;

/**
 * Fires {@code SPRINT_DISTANCE} and {@code SWIM_DISTANCE}, blank Target, Amount whole blocks covered: the one
 * producer of each, so a distance counts once for every reader of it (a track source paying on it, a stat file
 * summing it, a quest, an achievement).
 *
 * <p>Every tick it adds a player's horizontal speed times the tick to one running fraction per kind: a sprint on
 * the ground (not falling, not swimming), or a swim. Whole blocks go out at most once a {@value #FLUSH_MS} ms per
 * player, the fraction carried to the next, so a long run is a handful of moments rather than one a block. What a
 * player had not yet sent is dropped when they leave the server.
 */
public final class ZigTravelDistanceProducer extends EntityTickingSystem<EntityStore> {

    /** Distance covered at a sprint. */
    public static final String KIND_SPRINT = "SPRINT_DISTANCE";

    /** Distance covered swimming. */
    public static final String KIND_SWIM = "SWIM_DISTANCE";

    /** The shortest gap between two sends for one player. */
    static final long FLUSH_MS = 1000L;

    /** Per player, the blocks not yet sent and when they last sent. Transient and per process. */
    private static final Map<UUID, Travel> TRAVEL = new ConcurrentHashMap<>();

    /** Register the producer and the departure cleanup. Registration only, from {@code ProgressionDefaults.install}. */
    public static void install(@Nonnull PluginBase plugin) {
        plugin.getEntityStoreRegistry().registerSystem(new ZigTravelDistanceProducer());
        plugin.getEventRegistry().register(PlayerDisconnectEvent.class, ZigTravelDistanceProducer::onDisconnect);
    }

    @Nullable
    @Override
    public Query<EntityStore> getQuery() {
        return PlayerRef.getComponentType();
    }

    @Override
    public void tick(float dt, int index, @Nonnull ArchetypeChunk<EntityStore> chunk, @Nonnull Store<EntityStore> store,
            @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        try {
            Ref<EntityStore> ref = chunk.getReferenceTo(index);
            if (!ref.isValid()) {
                return;
            }
            PlayerRef player = PlayerAccess.playerRef(store, ref);
            UUID playerId = player == null ? null : player.getUuid();
            MovementStatesComponent statesComponent = store.getComponent(ref,
                    MovementStatesComponent.getComponentType());
            MovementStates states = statesComponent == null ? null : statesComponent.getMovementStates();
            if (playerId == null || states == null) {
                return;
            }
            Travel travel = TRAVEL.computeIfAbsent(playerId, id -> new Travel());
            double blocks = horizontalSpeed(store, ref) * dt;
            if (states.sprinting && states.onGround && !states.falling && !states.swimming) {
                travel.sprint += blocks;
            }
            if (states.swimming) {
                travel.swim += blocks;
            }
            long now = System.currentTimeMillis();
            if (now - travel.sentAt < FLUSH_MS) {
                return;
            }
            long sprinted = travel.takeSprint();
            long swum = travel.takeSwim();
            if (sprinted > 0L || swum > 0L) {
                travel.sentAt = now;
            }
            if (sprinted > 0L) {
                ProgressDispatch.fire(store, ref, commandBuffer, KIND_SPRINT, "", null, sprinted, null);
            }
            if (swum > 0L) {
                ProgressDispatch.fire(store, ref, commandBuffer, KIND_SWIM, "", null, swum, null);
            }
        } catch (Throwable t) {
            SafeLog.warn("[progression] travel-distance dispatch failed", t);
        }
    }

    /** Blocks a second across the ground, read off the entity's velocity; 0 with none. */
    private static double horizontalSpeed(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref) {
        Velocity velocity = store.getComponent(ref, Velocity.getComponentType());
        if (velocity == null) {
            return 0.0;
        }
        double vx = velocity.getX();
        double vz = velocity.getZ();
        double speed = Math.sqrt(vx * vx + vz * vz);
        return Double.isFinite(speed) ? speed : 0.0;
    }

    /** One player's running fractions. Touched only on their world's thread. */
    static final class Travel {
        double sprint;
        double swim;
        long sentAt;

        long takeSprint() {
            long whole = wholeOf(sprint);
            sprint -= whole;
            return whole;
        }

        long takeSwim() {
            long whole = wholeOf(swim);
            swim -= whole;
            return whole;
        }
    }

    /** The whole blocks in a running fraction, never below 0. Pure. */
    static long wholeOf(double blocks) {
        return blocks >= 1.0 && Double.isFinite(blocks) ? (long) blocks : 0L;
    }

    /** A player left the server: what they had not yet sent goes. */
    static void onDisconnect(@Nonnull PlayerDisconnectEvent event) {
        try {
            PlayerRef player = event.getPlayerRef();
            if (player != null && player.getUuid() != null) {
                TRAVEL.remove(player.getUuid());
            }
        } catch (Throwable t) {
            SafeLog.fine("[progression] a departing player's travel could not be dropped: " + t.getMessage());
        }
    }
}
