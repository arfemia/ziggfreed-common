package com.ziggfreed.common.effect.sync;

import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.annotation.Nonnull;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.dependency.Dependency;
import com.hypixel.hytale.component.dependency.Order;
import com.hypixel.hytale.component.dependency.SystemDependency;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.server.core.entity.effect.EffectControllerComponent;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatsSystems;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.stats.StatCarriers;
import com.ziggfreed.common.stats.condition.ConditionalStatSystems;

/**
 * The one pass that keeps every stat-to-effect family in step on every carrier (R860): each registered
 * {@link StatEffectChannel} (the per-type damage bonus, the resistance, speed and knockback) is asked of every living
 * entity with stats and effects, a player every tick and a creature on its turn of the carrier cadence
 * ({@link StatCarriers#due}). Ordered after the engine's stat-modifier recompute ({@code EntityStatsSystems.Recalculate}),
 * where a worn or utility-slot item's stats and an effect's reach the stat, and after the conditional holder pass,
 * where an {@code Onto} fold is written, so both count the same tick. A dead carrier is skipped (the engine clears its
 * effects; the first pass alive puts them back). Registered once, by {@link StatSyncBootstrap} (the system registry is
 * class-keyed); a family registers its channel ({@link #register}).
 */
public final class StatEffectSync extends EntityTickingSystem<EntityStore> {

    /** The registered channels, in registration order. */
    private static final CopyOnWriteArrayList<StatEffectChannel> CHANNELS = new CopyOnWriteArrayList<>();

    @Nonnull
    private final Query<EntityStore> query = StatCarriers.livingWithEffects();

    @Nonnull
    private final Set<Dependency<EntityStore>> dependencies = Set.of(
            new SystemDependency<>(Order.AFTER, EntityStatsSystems.Recalculate.class),
            new SystemDependency<>(Order.AFTER, ConditionalStatSystems.HolderPass.class));

    /** Add {@code channel} to the pass (the same channel again adds nothing). Set at setup. */
    public static void register(@Nonnull StatEffectChannel channel) {
        CHANNELS.addIfAbsent(channel);
    }

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
        if (CHANNELS.isEmpty() || !StatCarriers.due(store, archetypeChunk, index)) {
            return;
        }
        EntityStatMap stats = archetypeChunk.getComponent(index, EntityStatMap.getComponentType());
        EffectControllerComponent ctrl = archetypeChunk.getComponent(index, EffectControllerComponent.getComponentType());
        if (stats == null || ctrl == null) {
            return;
        }
        Ref<EntityStore> ref = archetypeChunk.getReferenceTo(index);
        for (StatEffectChannel channel : CHANNELS) {
            channel.keepInStep(commandBuffer, ref, stats, ctrl);
        }
    }
}
