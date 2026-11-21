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
import com.ziggfreed.common.stats.condition.BossRules;
import com.ziggfreed.common.stats.condition.EffectFamilies;
import com.ziggfreed.common.util.SafeLog;

/**
 * Takes the stuns a wearer sheds ({@link StunImmunity}) off every entity wearing an effect tagged
 * {@value StunControl#STUN_IMMUNE_TAG}, and every effect of a kind of control its rules shrug (zc-entity's
 * {@code BossRules}: an effect whose families name a kind, the game's own included), every tick, before the tick's
 * effect changes go out to the players watching ({@link EntityTrackerSystems#QUEUE_UPDATE_GROUP}), so a control landed
 * earlier in the same tick is gone before anyone is told it came on. An entity wearing no such effect costs one look
 * along its active effects; a server whose effects shed nothing and whose rules shrug no kind an effect family names
 * reads none.
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
            boolean sheds = StunImmunity.any();
            // The kinds some rule shrugs that some effect family names: none, and no rule can shed an effect.
            int shrugged = BossRules.mayShrugMask() & EffectFamilies.familyKindMask();
            if (!sheds && shrugged == 0) {
                return;
            }
            EffectControllerComponent effects = chunk.getComponent(index, EffectControllerComponent.getComponentType());
            if (effects == null) {
                return;
            }
            int[] active = effects.getActiveEffectIndexes();
            if (active == null || active.length == 0) {
                return;
            }
            if (sheds && active.length >= 2) {
                shedImmune(chunk, index, effects, active, commandBuffer);
            }
            if (shrugged != 0) {
                shedShrugged(chunk, index, effects, active, shrugged, commandBuffer);
            }
        } catch (Throwable t) {
            SafeLog.fine("[control] could not shed a control from an entity that refuses it: " + t.getMessage());
        }
    }

    /** The game's stuns an effect tagged {@value StunControl#STUN_IMMUNE_TAG} names, off its wearer. */
    private static void shedImmune(@Nonnull ArchetypeChunk<EntityStore> chunk, int index,
            @Nonnull EffectControllerComponent effects, @Nonnull int[] active,
            @Nonnull CommandBuffer<EntityStore> commandBuffer) {
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
    }

    /**
     * Every worn effect whose kinds of control (its families') the wearer's rules shrug now, off it. The rules are read
     * only for a wearer that carries an effect of a kind some rule shrugs ({@code possible}).
     */
    private static void shedShrugged(@Nonnull ArchetypeChunk<EntityStore> chunk, int index,
            @Nonnull EffectControllerComponent effects, @Nonnull int[] active, int possible,
            @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        int worn = 0;
        for (int effect : active) {
            worn |= EffectFamilies.controlKindMask(effect);
        }
        if ((worn & possible) == 0) {
            return;
        }
        Ref<EntityStore> ref = chunk.getReferenceTo(index);
        int shrugs = BossRules.shruggedMask(commandBuffer, ref) & worn;
        if (shrugs == 0) {
            return;
        }
        for (int effect : active) {
            if ((EffectFamilies.controlKindMask(effect) & shrugs) != 0 && effects.hasEffect(effect)) {
                effects.removeEffect(ref, effect, commandBuffer);
            }
        }
    }
}
