package com.ziggfreed.common.track.gain;

import javax.annotation.Nonnull;

import org.joml.Vector3d;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.EntityEventSystem;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;
import com.hypixel.hytale.server.core.modules.entity.component.HeadRotation;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.interaction.event.InteractionChainStartEvent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.entity.PlayerActivity;
import com.ziggfreed.common.track.source.ItemSpawnedMobs;
import com.ziggfreed.common.util.SafeLog;

/**
 * The watch that fills zc-core's {@link PlayerActivity} (what the {@code Idle} gain factor reads): each player's place
 * and view, sampled every tick and compared with the last ({@link Sight}), and every interaction a player starts
 * ({@link Started}), which also arms the spot for an item that stands a creature up ({@code source.ItemSpawnedMobs}).
 * A disconnect forgets the player ({@link #onDisconnect}). Registered by the track module's bootstrap.
 */
public final class ActivityWatch {

    private ActivityWatch() {
    }

    /** A leaving player is forgotten, so their next sight counts as active. */
    public static void onDisconnect(@Nonnull PlayerDisconnectEvent event) {
        try {
            PlayerRef player = event.getPlayerRef();
            PlayerActivity.forget(player == null ? null : player.getUuid());
        } catch (Throwable t) {
            SafeLog.fine("[track] could not forget a leaving player's activity: " + t.getMessage());
        }
    }

    /** Each player's place and view, handed to {@link PlayerActivity#observe} every tick. */
    public static final class Sight extends EntityTickingSystem<EntityStore> {

        @Nonnull
        @Override
        public Query<EntityStore> getQuery() {
            return Query.and(PlayerRef.getComponentType(), TransformComponent.getComponentType());
        }

        @Override
        public boolean isParallel(int archetypeChunkSize, int taskCount) {
            return false;
        }

        @Override
        public void tick(float dt, int index, @Nonnull ArchetypeChunk<EntityStore> chunk,
                @Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> commandBuffer) {
            try {
                PlayerRef player = chunk.getComponent(index, PlayerRef.getComponentType());
                TransformComponent transform = chunk.getComponent(index, TransformComponent.getComponentType());
                if (player == null || transform == null || player.getUuid() == null) {
                    return;
                }
                Vector3d at = transform.getPosition();
                Ref<EntityStore> ref = chunk.getReferenceTo(index);
                HeadRotation head = store.getComponent(ref, HeadRotation.getComponentType());
                Rotation3f look = head != null ? head.getRotation() : transform.getRotation();
                if (at == null || look == null) {
                    return;
                }
                PlayerActivity.observe(player.getUuid(), at.x, at.y, at.z, look.yaw(), look.pitch(),
                        System.currentTimeMillis());
            } catch (Throwable t) {
                SafeLog.fine("[track] a player's activity could not be read: " + t.getMessage());
            }
        }
    }

    /**
     * A player starting an interaction themselves is active ({@link #playerStarted}); any chain on a player whose root
     * runs the engine's {@code SpawnNPC} also arms the spot for the creature it stands up.
     */
    public static final class Started extends EntityEventSystem<EntityStore, InteractionChainStartEvent> {

        public Started() {
            super(InteractionChainStartEvent.class);
        }

        @Nonnull
        @Override
        public Query<EntityStore> getQuery() {
            return PlayerRef.getComponentType();
        }

        @Override
        public void handle(int index, @Nonnull ArchetypeChunk<EntityStore> chunk, @Nonnull Store<EntityStore> store,
                @Nonnull CommandBuffer<EntityStore> commandBuffer, @Nonnull InteractionChainStartEvent event) {
            try {
                PlayerRef player = chunk.getComponent(index, PlayerRef.getComponentType());
                if (player == null || player.getUuid() == null) {
                    return;
                }
                long now = System.currentTimeMillis();
                if (playerStarted(event)) {
                    PlayerActivity.acted(player.getUuid(), now);
                }
                if (!ItemSpawnedMobs.spawnsCreature(event.getRootInteractionId())) {
                    return;
                }
                TransformComponent transform = chunk.getComponent(index, TransformComponent.getComponentType());
                World world = store.getExternalData().getWorld();
                Vector3d at = transform == null ? null : transform.getPosition();
                if (world != null && at != null) {
                    ItemSpawnedMobs.armed(world, at.x, at.y, at.z, now);
                }
            } catch (Throwable t) {
                SafeLog.fine("[track] a player's interaction could not be read: " + t.getMessage());
            }
        }

        /**
         * Did the player start this chain themselves? The engine numbers a chain the client sent from 0 up and one the
         * server starts on the entity below 0 ({@code InteractionManager.executeChain0}), so a chain an item or a mod
         * runs by itself never counts as the player being active.
         */
        static boolean playerStarted(@Nonnull InteractionChainStartEvent event) {
            try {
                return event.getChain() == null || event.getChain().getChainId() >= 0;
            } catch (Throwable t) {
                return true;
            }
        }
    }
}
