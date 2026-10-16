package com.ziggfreed.common.effect.resist;

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

/**
 * Every tick, for every living player, keeps their damage resistance effects in step with their
 * {@code DamageResist_<Type>} stats ({@link DamageResistReconciler#keepInStep}). Ordered after the engine's
 * own stat-modifier recompute ({@code EntityStatsSystems.Recalculate}), which is where a worn or
 * utility-slot item's stats, and an effect's, reach the stat: read any earlier and a swap is a tick
 * behind. A dead player is skipped (the engine clears their effects on death and again on respawn, and
 * the first tick alive puts them back). Registered once, by {@link ResistBootstrap} (the system registry
 * is class-keyed).
 */
public final class DamageResistSyncSystem extends EntityTickingSystem<EntityStore> {

    @Nonnull
    private final Query<EntityStore> query = Query.and(PlayerRef.getComponentType(),
            EntityStatMap.getComponentType(), EffectControllerComponent.getComponentType(),
            Query.not(DeathComponent.getComponentType()));

    @Nonnull
    private final Set<Dependency<EntityStore>> dependencies = Set.of(
            new SystemDependency<>(Order.AFTER, EntityStatsSystems.Recalculate.class));

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
        DamageResistReconciler.keepInStep(commandBuffer, archetypeChunk.getReferenceTo(index), stats, ctrl);
    }
}
