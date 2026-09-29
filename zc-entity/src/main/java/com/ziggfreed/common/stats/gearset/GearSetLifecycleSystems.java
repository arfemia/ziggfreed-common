package com.ziggfreed.common.stats.gearset;

import javax.annotation.Nonnull;

import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.RefSystem;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.RespawnSystems;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.util.SafeLog;

/**
 * The two entity lifecycle moments the gear-set engine answers beyond the bridge's own equip
 * triggers, each a thin system handing off to {@link GearSets}. Registered once each by
 * {@code EntityBootstrap.installGearSets} (the registry is class-keyed).
 */
public final class GearSetLifecycleSystems {

    private GearSetLifecycleSystems() {
    }

    /**
     * A player respawned: the engine's own respawn base ({@code RespawnSystems.OnRespawnSystem}, a
     * reaction to the {@code DeathComponent} coming off), beside the engine's
     * {@code ClearEntityEffectsRespawnSystem}, which clears every effect on the same removal. Hands
     * the player to {@link GearSets#onRespawned}, which recomputes on the world thread afterwards so
     * the set's look comes back.
     */
    public static final class Respawned extends RespawnSystems.OnRespawnSystem {

        @Nonnull
        @Override
        public Query<EntityStore> getQuery() {
            return PlayerRef.getComponentType();
        }

        @Override
        public void onComponentRemoved(@Nonnull Ref<EntityStore> ref, @Nonnull DeathComponent component,
                @Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> commandBuffer) {
            GearSets.onRespawned(ref);
        }
    }

    /**
     * A player's entity left its store: hands the player and the engine's reason to
     * {@link GearSets#onEntityRemoved}, which forgets the row unless the move is a world change.
     */
    public static final class Left extends RefSystem<EntityStore> {

        @Nonnull
        @Override
        public Query<EntityStore> getQuery() {
            return PlayerRef.getComponentType();
        }

        @Override
        public void onEntityAdded(@Nonnull Ref<EntityStore> ref, @Nonnull AddReason reason,
                @Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        }

        @Override
        public void onEntityRemove(@Nonnull Ref<EntityStore> ref, @Nonnull RemoveReason reason,
                @Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> commandBuffer) {
            try {
                PlayerRef player = store.getComponent(ref, PlayerRef.getComponentType());
                GearSets.onEntityRemoved(player == null ? null : player.getUuid(), reason);
            } catch (Throwable t) {
                SafeLog.warn("[gearset] forgetting a departed player failed: " + t.getMessage());
            }
        }
    }
}
