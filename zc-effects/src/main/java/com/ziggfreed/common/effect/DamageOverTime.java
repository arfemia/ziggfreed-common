package com.ziggfreed.common.effect;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.EntityEffect;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.OverlapBehavior;
import com.hypixel.hytale.server.core.entity.effect.ActiveEntityEffect;
import com.hypixel.hytale.server.core.entity.effect.EffectControllerComponent;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.server.combat.DamageCalculator;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.effect.duration.EffectDurations;
import com.ziggfreed.common.util.SafeLog;

/**
 * Puts a damage-over-time effect on an entity for someone, at a strength: the engine's own effect deals every
 * tick (its {@code DamageCalculator}, its {@code DamageCalculatorCooldown} cadence, its {@code DamageEffects}),
 * each tick credited to the owner (kill credit, the owner's outgoing damage lines, a hit step reading the
 * attacker), and this only says how hard a tick hits, through the instance's damage multiplier, and how long it
 * lasts, through the effect families' {@code Duration} rules ({@link EffectDurations#lengthAsLanded}).
 *
 * <p><b>Stacks.</b> The engine keeps one instance per entity and effect. A repeat while it is on adds one stack,
 * up to the cap, and starts its time again from the full length; each tick then deals {@code perStack} damage a
 * stack, the newest {@code perStack} holding. Whoever repeats it takes over the credit, so two appliers build one
 * shared set of stacks. The count is kept per active instance in a weak table, so an instance that ends drops out
 * on its own, and an instance this did not put on reads as one stack.
 *
 * <p>Transient and single-process; world thread only; never throws.
 */
public final class DamageOverTime {

    /** Each active instance this put on, and its stacks. */
    private static final Map<ActiveEntityEffect, Integer> STACKS = Collections.synchronizedMap(new WeakHashMap<>());

    private static volatile boolean failureWarned;

    private DamageOverTime() {
    }

    /**
     * Put {@code effectId} on {@code target} for {@code seconds} (before the Duration rules), owned by
     * {@code owner}, each tick dealing {@code perStack} damage a stack.
     *
     * @param owner     who the ticks are credited to; null or the target itself leaves the effect unowned
     * @param maxStacks the most stacks it builds to (at least one)
     * @return the stacks now on, or 0 when nothing landed: an unknown effect, one that deals no damage of its own
     *         (no {@code DamageCalculator}) or is infinite, a target with no effect controller, a refusal by the
     *         effect's {@code ApplyConditions}, or a throw
     */
    public static int land(@Nonnull ComponentAccessor<EntityStore> accessor, @Nonnull Ref<EntityStore> target,
            @Nonnull String effectId, float seconds, @Nullable Ref<EntityStore> owner, float perStack, int maxStacks) {
        try {
            if (!target.isValid() || effectId.isBlank() || !(seconds > 0f) || !(perStack > 0f)) {
                return 0;
            }
            int index = EntityEffect.getAssetMap().getIndex(effectId);
            EntityEffect effect = index == Integer.MIN_VALUE ? null : EntityEffect.getAssetMap().getAsset(index);
            if (effect == null || effect.isInfinite() || effect.getDamageCalculator() == null) {
                return 0;
            }
            EffectControllerComponent ctrl = accessor.getComponent(target, EffectControllerComponent.getComponentType());
            if (ctrl == null) {
                return 0;
            }
            ActiveEntityEffect before = ctrl.getActiveEffects().get(index);
            if (before != null && before.isInfinite()) {
                return 0;
            }
            int cap = Math.max(1, maxStacks);
            int stacks = before == null ? 1 : Math.min(cap, stacksOf(before) + 1);
            Ref<EntityStore> credit = owner != null && owner.isValid() && !owner.equals(target) ? owner : null;
            float lasting = EffectDurations.lengthAsLanded(accessor, target, index, credit, seconds);
            if (!ctrl.addEffect(target, index, effect, lasting, OverlapBehavior.OVERWRITE, accessor, credit)) {
                return 0;
            }
            ActiveEntityEffect active = ctrl.getActiveEffects().get(index);
            if (active == null) {
                return 0;
            }
            float base = baseDamage(effect.getDamageCalculator(), active.getInitialDuration());
            if (!(base > 0f)) {
                return 0;
            }
            active.setDamageMultiplier(perStack * stacks / base);
            STACKS.put(active, stacks);
            EffectDurations.markLanded(active, lasting);
            return stacks;
        } catch (Throwable t) {
            if (!failureWarned) {
                failureWarned = true;
                SafeLog.warn("[dot] putting a damage over time on failed (named once): " + t);
            }
            return 0;
        }
    }

    /** The stacks {@code active} carries: what this put on, else one. */
    public static int stacksOf(@Nonnull ActiveEntityEffect active) {
        Integer held = STACKS.get(active);
        return held == null ? 1 : Math.max(1, held);
    }

    /**
     * The damage one tick of a calculator deals before any strength, across its types and the middle of its random
     * spread; 0 when it deals none. {@code durationSeconds} matters only to a {@code DPS} calculator.
     */
    public static float baseDamage(@Nullable DamageCalculator calculator, double durationSeconds) {
        if (calculator == null) {
            return 0f;
        }
        float[] range = new float[2];
        calculator.computeDamageRange(durationSeconds, range);
        float mid = (range[0] + range[1]) / 2f;
        return Float.isFinite(mid) ? Math.max(0f, mid) : 0f;
    }
}
