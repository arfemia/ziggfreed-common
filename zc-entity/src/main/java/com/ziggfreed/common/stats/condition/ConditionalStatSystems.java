package com.ziggfreed.common.stats.condition;

import java.util.Set;
import java.util.UUID;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.SystemGroup;
import com.hypixel.hytale.component.dependency.Dependency;
import com.hypixel.hytale.component.dependency.Order;
import com.hypixel.hytale.component.dependency.SystemDependency;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.RefSystem;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageEventSystem;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageModule;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatsSystems;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.util.SafeLog;

/**
 * The three systems behind {@link ConditionalStats}, registered once by {@link ConditionBootstrap} (the system
 * registry is class-keyed): the holder pass, the first-hit record and the forgetting of an entity that left its store.
 */
public final class ConditionalStatSystems {

    private ConditionalStatSystems() {
    }

    /**
     * Every tick, for every living player, reads their conditional stats again when their time has come
     * ({@link ConditionalStats#EVALUATE_EVERY_NANOS}). Ordered after the engine's stat-modifier recompute, where gear
     * and effects reach a stat's maximum, so a reading never lags a swap.
     */
    public static final class HolderPass extends EntityTickingSystem<EntityStore> {

        @Nonnull
        private final Query<EntityStore> query = Query.and(PlayerRef.getComponentType(),
                EntityStatMap.getComponentType(), Query.not(DeathComponent.getComponentType()));

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
            if (stats == null) {
                return;
            }
            ConditionalStats.evaluate(store, archetypeChunk.getReferenceTo(index), stats, ConditionalStats.now(store),
                    System.nanoTime());
        }
    }

    /**
     * Notes every player's landed direct hit for {@code Hit.FirstHit} ({@link FirstHits}), in the Inspect damage group,
     * so every read of the hit in the Filter group saw it as it was before.
     */
    public static final class FirstHitRecord extends DamageEventSystem {

        @Nonnull
        @Override
        public SystemGroup<EntityStore> getGroup() {
            return DamageModule.get().getInspectDamageGroup();
        }

        @Nullable
        @Override
        public Query<EntityStore> getQuery() {
            return Query.any();
        }

        @Override
        public void handle(int index, @Nonnull ArchetypeChunk<EntityStore> chunk, @Nonnull Store<EntityStore> store,
                @Nonnull CommandBuffer<EntityStore> commandBuffer, @Nonnull Damage damage) {
            try {
                if (damage.isCancelled() || HitReads.isOverTime(damage)
                        || !(damage.getSource() instanceof Damage.EntitySource source)) {
                    return;
                }
                Ref<EntityStore> attacker = source.getRef();
                if (attacker == null || !attacker.isValid()
                        || store.getComponent(attacker, PlayerRef.getComponentType()) == null) {
                    return;
                }
                UUID id = FirstHits.idOf(store, attacker);
                if (id != null) {
                    FirstHits.record(id, chunk.getReferenceTo(index));
                }
            } catch (Throwable t) {
                SafeLog.fine("[condition] recording a hit failed: " + t.getMessage());
            }
        }
    }

    /**
     * A living entity left its store (a death, an unload, a world change): forget it as a hit target and as a holder.
     */
    public static final class Left extends RefSystem<EntityStore> {

        @Nonnull
        @Override
        public Query<EntityStore> getQuery() {
            return EntityStatMap.getComponentType();
        }

        @Override
        public void onEntityAdded(@Nonnull Ref<EntityStore> ref, @Nonnull AddReason reason,
                @Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        }

        @Override
        public void onEntityRemove(@Nonnull Ref<EntityStore> ref, @Nonnull RemoveReason reason,
                @Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> commandBuffer) {
            FirstHits.forget(ref);
            ConditionalStats.forget(ref);
        }
    }
}
