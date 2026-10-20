package com.ziggfreed.common.effect.movement;

import java.util.Set;

import javax.annotation.Nonnull;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.dependency.Dependency;
import com.hypixel.hytale.component.dependency.Order;
import com.hypixel.hytale.component.dependency.SystemDependency;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.server.core.entity.effect.EffectControllerComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatsSystems;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.stats.condition.ConditionalStatSystems;

/**
 * Every tick, for every living player, keeps their speed and knockback effects in step with their movement stats
 * ({@link MovementReconciler#keepInStep}). Ordered after the engine's stat-modifier recompute
 * ({@code EntityStatsSystems.Recalculate}), where a worn or utility-slot item's stats and an effect's reach the stat.
 * A dead player is skipped (the engine clears their effects; the first tick alive puts them back). Registered once,
 * by {@link MovementBootstrap} (the system registry is class-keyed).
 */
public final class MovementSyncSystem extends EntityTickingSystem<EntityStore> {

    @Nonnull
    private final Query<EntityStore> query = Query.and(PlayerRef.getComponentType(),
            EntityStatMap.getComponentType(), EffectControllerComponent.getComponentType(),
            Query.not(DeathComponent.getComponentType()));

    @Nonnull
    private final Set<Dependency<EntityStore>> dependencies = Set.of(
            new SystemDependency<>(Order.AFTER, EntityStatsSystems.Recalculate.class),
            // A conditional knockback stat (zc-entity's Onto fold) is written there, so it counts the same tick.
            new SystemDependency<>(Order.AFTER, ConditionalStatSystems.HolderPass.class));

    @Nonnull
    @Override
    public Query<EntityStore> getQuery() {
        return query;
    }

    @Nonnull
    @Override
    public Set<Dependency<EntityStore>> getDependencies() {
        return dependencies;
    }

    @Override
    public boolean isParallel(int archetypeChunkSize, int taskCount) {
        return false;
    }

    @Override
    public void tick(float dt, int index, @Nonnull ArchetypeChunk<EntityStore> archetypeChunk,
            @Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        EntityStatMap stats = archetypeChunk.getComponent(index, EntityStatMap.getComponentType());
        EffectControllerComponent ctrl = archetypeChunk.getComponent(index, EffectControllerComponent.getComponentType());
        if (stats == null || ctrl == null) {
            return;
        }
        MovementReconciler.keepInStep(commandBuffer, archetypeChunk.getReferenceTo(index), stats, ctrl);
    }
}
