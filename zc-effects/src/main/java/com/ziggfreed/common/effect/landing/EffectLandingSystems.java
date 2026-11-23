package com.ziggfreed.common.effect.landing;

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
import com.hypixel.hytale.server.core.entity.effect.EffectControllerComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageEventSystem;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageModule;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageSourceType;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.stats.StatCarriers;
import com.ziggfreed.common.util.SafeLog;

/**
 * The systems behind {@link EffectLandings}, registered once by {@link LandingBootstrap} (the system registry is
 * class-keyed): the carrier pass, the watch a hit puts on its target, and the forgetting of an entity that left its
 * store.
 */
public final class EffectLandingSystems {

    private EffectLandingSystems() {
    }

    /**
     * Every living carrier's effects (a heal over time a friend put on, a stun on them, a burn a creature put on
     * another): a player every tick, a creature on its turn of the carrier cadence ({@link StatCarriers#due}, R860),
     * and every tick a creature a hit or a control landed on lately ({@link EffectLandings#watch}), so its effects are
     * read as they land.
     */
    public static final class Carriers extends EntityTickingSystem<EntityStore> {

        @Nonnull
        private final Query<EntityStore> query = StatCarriers.livingWithEffects();

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
            Ref<EntityStore> ref = archetypeChunk.getReferenceTo(index);
            if (StatCarriers.due(store, archetypeChunk, index) || EffectLandings.watched(ref, System.nanoTime())) {
                EffectLandings.look(store, commandBuffer, ref);
            }
        }
    }

    /**
     * Any attacker's landed direct hit on a creature puts the creature under watch, in the Inspect damage group (a
     * player is read every tick already).
     */
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
                if (attacker == null || !attacker.isValid() || StatCarriers.isPlayer(chunk)
                        || !chunk.getArchetype().contains(EffectControllerComponent.getComponentType())) {
                    return;
                }
                EffectLandings.watch(chunk.getReferenceTo(index), System.nanoTime());
            } catch (Throwable t) {
                SafeLog.fine("[landing] watching a hit target failed: " + t.getMessage());
            }
        }
    }

    /** An entity left its store: drop its watch and its record. */
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
            EffectLandings.forget(ref);
        }
    }
}
