package com.ziggfreed.common.encounter.run;

import javax.annotation.Nonnull;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.modifier.Modifier;
import com.hypixel.hytale.server.core.modules.entitystats.modifier.StaticModifier;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.effect.outgoing.DamageBonusReconciler;
import com.ziggfreed.common.stats.StatIndexCache;
import com.ziggfreed.common.stats.damage.TypedDamageBonuses;
import com.ziggfreed.common.util.SafeLog;

/**
 * A fight's damage step on one of its creatures: a whole percent put on as one keyed modifier on the every-type damage
 * bonus stat ({@code DamageBonus}, R860), the stat every attacker reads, so the library's own outgoing-damage pass
 * raises every hit the creature deals by it, summed with its other bonuses under its {@code DamageBonusMax}. Each kind
 * of step keeps its own key, so an endless wave's step ({@code wave/WaveDamage}) and a run's ({@link #RUN_KEY}) add
 * up rather than overwrite each other. The modifier is saved with the creature. World thread only.
 */
public final class DamageStep {

    /** The key a run's step (a dungeon tier's, {@code seam/RunScale}) sits under. */
    public static final String RUN_KEY = "ziggfreedcommon:run_damage";

    private DamageStep() {
    }

    /**
     * Put {@code percent} on {@code creature} under {@code key}, and bring its outgoing bonus in step at once: nothing
     * for 0 or less. Answers whether it is on. Never throws.
     */
    public static boolean apply(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> creature,
            @Nonnull String key, int percent) {
        if (percent <= 0 || !creature.isValid()) {
            return false;
        }
        try {
            EntityStatMap stats = store.getComponent(creature, EntityStatMap.getComponentType());
            int index = StatIndexCache.resolve(TypedDamageBonuses.EVERY_TYPE_STAT_ID);
            if (stats == null || index == StatIndexCache.UNRESOLVED) {
                return false;
            }
            stats.putModifier(index, key, new StaticModifier(Modifier.ModifierTarget.MAX,
                    StaticModifier.CalculationType.ADDITIVE, percent));
            DamageBonusReconciler.sync(store, creature);
            return true;
        } catch (Throwable t) {
            SafeLog.warn("[encounter] a damage step could not go on a creature: " + t.getMessage());
            return false;
        }
    }
}
