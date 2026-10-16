package com.ziggfreed.common.effect;

import javax.annotation.Nonnull;

import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.EntityEffect;
import com.hypixel.hytale.server.core.entity.effect.ActiveEntityEffect;
import com.hypixel.hytale.server.core.entity.effect.EffectControllerComponent;
import com.hypixel.hytale.server.core.modules.entity.condition.Condition;
import com.hypixel.hytale.server.core.modules.entity.livingentity.LivingEntityEffectSystem;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * Hands the engine's effect controller an apply whose lifetime the asset authors ({@link #asAuthored})
 * or that holds until removed ({@link #infinite}), never re-adding an infinite effect the entity already
 * wears. Every such apply in this library goes through here: {@link NativeEffectUtil},
 * {@code EntityEffectService}, {@code RuntimeEffectGrid} and {@code Costumes}.
 *
 * <p>The engine's {@code addInfiniteEffect} queues an {@code EffectOp.Add} for the entity's viewers on
 * every call, whether or not the effect is already on, and a client meeting an Add for an effect it
 * already shows starts a fresh set of that effect's particles without stopping the old ones: a mob
 * whose aura is applied again after players see it drags a trail of them. So an infinite add of an
 * effect already infinite on the entity hands nothing to the engine and answers what the engine would
 * have answered: whether the effect's {@code ApplyConditions} hold. Everything else goes to the engine
 * exactly as before: a timed effect, whose repeat refreshes or extends it on purpose, a first apply,
 * and an infinite add over a timed instance of the same effect (the engine makes that one infinite).
 *
 * <p>World thread only. The caller resolves the index and the asset and catches the throw, as
 * {@link NativeEffectUtil} and {@code EntityEffectService} do.
 */
public final class EffectAdds {

    private EffectAdds() {
    }

    /**
     * Add {@code effect} with the asset's own {@code Duration}, {@code OverlapBehavior} and
     * {@code Infinite} flag, through the engine's index-aware {@code addEffect}, unless it is an
     * {@code Infinite} asset the entity already wears with no expiry.
     *
     * @return the engine's answer, or for a skipped repeat whether the effect's conditions hold
     */
    public static boolean asAuthored(@Nonnull EffectControllerComponent ctrl, @Nonnull Ref<EntityStore> ref,
            int index, @Nonnull EntityEffect effect, @Nonnull ComponentAccessor<EntityStore> accessor) {
        if (repeatsWorn(ctrl, index, effect.isInfinite())) {
            return conditionsHold(ref, effect, accessor);
        }
        return ctrl.addEffect(ref, index, effect, accessor);
    }

    /**
     * Add {@code effect} with no expiry, whatever its asset authors, through the engine's
     * {@code addInfiniteEffect}, unless the entity already wears it with no expiry.
     *
     * @return the engine's answer, or for a skipped repeat whether the effect's conditions hold
     */
    public static boolean infinite(@Nonnull EffectControllerComponent ctrl, @Nonnull Ref<EntityStore> ref,
            int index, @Nonnull EntityEffect effect, @Nonnull ComponentAccessor<EntityStore> accessor) {
        if (repeatsWorn(ctrl, index, true)) {
            return conditionsHold(ref, effect, accessor);
        }
        return ctrl.addInfiniteEffect(ref, index, effect, accessor);
    }

    /**
     * Whether an add of the effect at {@code index}, infinite when {@code infiniteAdd}, would only repeat
     * an effect the entity already wears with no expiry: the one add that changes nothing on the server
     * and still sends its viewers a fresh Add.
     */
    static boolean repeatsWorn(@Nonnull EffectControllerComponent ctrl, int index, boolean infiniteAdd) {
        if (!infiniteAdd) {
            return false;
        }
        ActiveEntityEffect live = ctrl.getActiveEffects().get(index);
        return live != null && live.isInfinite();
    }

    /**
     * The engine's own first check on every add ({@code LivingEntityEffectSystem.canApplyEffect}),
     * asked only when the effect writes {@code ApplyConditions}: with none the engine answers true, so the
     * common repeat costs one map read.
     */
    private static boolean conditionsHold(@Nonnull Ref<EntityStore> ref, @Nonnull EntityEffect effect,
            @Nonnull ComponentAccessor<EntityStore> accessor) {
        Condition[] conditions = effect.getApplyConditions();
        if (conditions == null || conditions.length == 0) {
            return true;
        }
        return LivingEntityEffectSystem.canApplyEffect(ref, effect, accessor);
    }
}
