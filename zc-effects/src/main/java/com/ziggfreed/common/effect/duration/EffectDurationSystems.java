package com.ziggfreed.common.effect.duration;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.SystemGroup;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.RefSystem;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.component.system.tick.TickingSystem;
import com.hypixel.hytale.server.core.entity.effect.EffectControllerComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageEventSystem;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageModule;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageSourceType;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.util.SafeLog;

/**
 * The systems behind {@link EffectDurations}, registered once by {@link DurationBootstrap} (the system registry is
 * class-keyed): the player pass, the hit-target pass, the watch a player's hit puts on its target, and the forgetting
 * of an entity that left its store.
 */
public final class EffectDurationSystems {

    private EffectDurationSystems() {
    }

    /** Every tick, every living player's effects (a heal over time a friend put on, a stun on them). */
    public static final class Players extends EntityTickingSystem<EntityStore> {

        @Nonnull
        private final Query<EntityStore> query = Query.and(PlayerRef.getComponentType(),
                EffectControllerComponent.getComponentType(), Query.not(DeathComponent.getComponentType()));

        @Nonnull
        @Override
        public Query<EntityStore> getQuery() {
            return query;
        }

        @Override
        public boolean isParallel(int archetypeChunkSize, int taskCount) {
            return false;
        }

        @Override
        public void tick(float dt, int index, @Nonnull ArchetypeChunk<EntityStore> archetypeChunk,
                @Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> commandBuffer) {
            EffectDurations.adjust(store, archetypeChunk.getReferenceTo(index));
        }
    }

    /** Once per world tick, every entity a player's hit landed on lately (a burn, a slow, a root you put on it). */
    public static final class Watched extends TickingSystem<EntityStore> {

        @Override
        public void tick(float dt, int systemIndex, @Nonnull Store<EntityStore> store) {
            for (Ref<EntityStore> ref : EffectDurations.watchedIn(store, System.nanoTime())) {
                EffectDurations.adjust(store, ref);
            }
        }
    }

    /** A player's landed direct hit puts its target under watch, in the Inspect damage group. */
    public static final class Watch extends DamageEventSystem {

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
                if (damage.isCancelled() || !(damage.getSource() instanceof Damage.EntitySource source)
                        || source.getSourceType() == DamageSourceType.DoT) {
                    return;
                }
                Ref<EntityStore> attacker = source.getRef();
                if (attacker == null || !attacker.isValid()
                        || store.getComponent(attacker, PlayerRef.getComponentType()) == null) {
                    return;
                }
                EffectDurations.watch(chunk.getReferenceTo(index), System.nanoTime());
            } catch (Throwable t) {
                SafeLog.fine("[duration] watching a hit target failed: " + t.getMessage());
            }
        }
    }

    /** A watched entity left its store: drop its watch. */
    public static final class Left extends RefSystem<EntityStore> {

        @Nonnull
        @Override
        public Query<EntityStore> getQuery() {
            return EffectControllerComponent.getComponentType();
        }

        @Override
        public void onEntityAdded(@Nonnull Ref<EntityStore> ref, @Nonnull AddReason reason,
                @Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        }

        @Override
        public void onEntityRemove(@Nonnull Ref<EntityStore> ref, @Nonnull RemoveReason reason,
                @Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> commandBuffer) {
            EffectDurations.forget(ref);
        }
    }
}
