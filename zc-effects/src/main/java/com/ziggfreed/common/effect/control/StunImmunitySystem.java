package com.ziggfreed.common.effect.control;

import java.util.Set;

import javax.annotation.Nonnull;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.dependency.Dependency;
import com.hypixel.hytale.component.dependency.Order;
import com.hypixel.hytale.component.dependency.SystemGroupDependency;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.server.core.entity.effect.EffectControllerComponent;
import com.hypixel.hytale.server.core.modules.entity.tracker.EntityTrackerSystems;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.util.SafeLog;

/**
 * Takes the stuns a wearer sheds ({@link StunImmunity}) off every entity wearing an effect tagged
 * {@value StunControl#STUN_IMMUNE_TAG}, every tick, before the tick's effect changes go out to the players watching
 * ({@link EntityTrackerSystems#QUEUE_UPDATE_GROUP}), so a stun landed earlier in the same tick is gone before anyone
 * is told it came on. An entity wearing no such effect costs one look along its active effects; a server whose effects
 * shed nothing reads none.
 */
public final class StunImmunitySystem extends EntityTickingSystem<EntityStore> {

    @Nonnull
    private final Set<Dependency<EntityStore>> dependencies =
            Set.of(new SystemGroupDependency<>(Order.BEFORE, EntityTrackerSystems.QUEUE_UPDATE_GROUP));

    @Nonnull
    @Override
    public Query<EntityStore> getQuery() {
        return EffectControllerComponent.getComponentType();
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
    public void tick(float dt, int index, @Nonnull ArchetypeChunk<EntityStore> chunk, @Nonnull Store<EntityStore> store,
            @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        try {
            if (!StunImmunity.any()) {
                return;
            }
            EffectControllerComponent effects = chunk.getComponent(index, EffectControllerComponent.getComponentType());
            if (effects == null) {
                return;
            }
            int[] active = effects.getActiveEffectIndexes();
            if (active == null || active.length < 2) {
                return;
            }
            Ref<EntityStore> ref = null;
            for (int worn : active) {
                int[] shed = StunImmunity.shedBy(worn);
                if (shed == null) {
                    continue;
                }
                for (int stun : shed) {
                    if (effects.hasEffect(stun)) {
                        if (ref == null) {
                            ref = chunk.getReferenceTo(index);
                        }
                        effects.removeEffect(ref, stun, commandBuffer);
                    }
                }
            }
        } catch (Throwable t) {
            SafeLog.fine("[control] could not shed a stun from an immune entity: " + t.getMessage());
        }
    }
}
