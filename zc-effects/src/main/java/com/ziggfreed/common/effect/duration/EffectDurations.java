package com.ziggfreed.common.effect.duration;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.EntityEffect;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.OverlapBehavior;
import com.hypixel.hytale.server.core.entity.effect.ActiveEntityEffect;
import com.hypixel.hytale.server.core.entity.effect.EffectControllerComponent;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatValue;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.effect.landing.EffectLandings;
import com.ziggfreed.common.stats.StatIndexCache;
import com.ziggfreed.common.stats.condition.ControlKinds;
import com.ziggfreed.common.stats.condition.EffectFamilies;
import com.ziggfreed.common.util.SafeLog;

/**
 * How long an effect lasts as it lands, by stats (R757): an effect family's {@code Duration} rule
 * ({@code EffectFamilyAsset.Duration}) names a stat of whoever put the effect on, each point a second more
 * ({@code EffectDuration_Dot}: your poisons last longer), and one of whoever carries it, each point a percent off
 * held to the family's cap ({@code EffectTaken_Control}: stuns on you end sooner); and a kind of control's
 * {@code MaxSeconds} (zc-entity's {@code ControlKinds}, the Control Kinds screen) is the longest an effect of that kind
 * lasts on anyone. A fixed control (its effect tagged {@code "ZigControl": ["Fixed"]}) skips what the Crowd Control
 * settings say: the carrier's percent off, the owner's added seconds.
 *
 * <p><b>When.</b> The engine has no moment for an effect landing, so this reads every active effect through the one
 * landing watch ({@link EffectLandings}: a player each tick, a creature a few times a second, and each tick a
 * creature a hit landed on lately), the first time it is seen and again whenever its remaining time has grown since
 * (a re-application that refreshed it), and changes its remaining time once through the engine's own
 * {@code addEffect} with the owner kept. An applier that puts an effect on itself reads the rules as it lands
 * ({@link #landOwned}, {@link #lengthAsLanded} then {@link #markLanded}) so the watch never changes it twice.
 *
 * <p>What was seen is kept per active effect object in a weak table, so a removed effect drops out on its own.
 * Transient and single-process; world thread only; never throws.
 */
public final class EffectDurations {

    /** A change in remaining time smaller than this is not a refresh. */
    private static final float EPSILON = 0.05f;

    /** Each active effect seen, and its remaining time as last left (seconds). */
    private static final Map<ActiveEntityEffect, Float> SEEN = Collections.synchronizedMap(new WeakHashMap<>());

    /** The length pass as a reader of the landing watch, registered by {@link DurationBootstrap}. */
    static final EffectLandings.Watcher WATCHER = new EffectLandings.Watcher() {
        @Override
        public boolean listening() {
            return EffectFamilies.anyDurationRule() || ControlKinds.cappedMask() != 0;
        }

        @Override
        public void seen(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> carrier,
                @Nonnull ActiveEntityEffect active) {
            adjust(store, carrier, active);
        }
    };

    private static volatile boolean failureWarned;

    private EffectDurations() {
    }

    /**
     * Read one active effect of {@code ref}: if it landed or was refreshed since it was last read, change its
     * remaining time by its families' rules and its kinds' cap. Never throws.
     */
    private static void adjust(@Nonnull ComponentAccessor<EntityStore> accessor, @Nonnull Ref<EntityStore> ref,
            @Nonnull ActiveEntityEffect active) {
        try {
            if (active.isInfinite()) {
                return;
            }
            float remaining = active.getRemainingDuration();
            Float last = SEEN.get(active);
            SEEN.put(active, remaining);
            if (last != null && remaining <= last + EPSILON) {
                return; // seen, and only ticking down since
            }
            int index = active.getEntityEffectIndex();
            if (!hasRule(index)) {
                return;
            }
            float changed = changedLength(accessor, ref, index, active.getOwner(), remaining);
            if (Math.abs(changed - remaining) < EPSILON) {
                return;
            }
            EntityEffect effect = EntityEffect.getAssetMap().getAsset(index);
            EffectControllerComponent ctrl = accessor.getComponent(ref, EffectControllerComponent.getComponentType());
            if (effect == null || ctrl == null) {
                return;
            }
            ctrl.addEffect(ref, index, effect, changed, OverlapBehavior.OVERWRITE, accessor, active.getOwner());
            SEEN.put(active, changed);
        } catch (Throwable t) {
            warnOnce("changing an effect's length", t);
        }
    }

    /**
     * How long an effect lasts as it lands, for an applier that puts it on itself (a stacking damage over time, whose
     * refresh may shorten what is left, which the watch would not read as a landing): {@code seconds} through the
     * Duration rules of every family the effect at {@code effectIndex} is in, {@code owner}'s added seconds and
     * {@code carrier}'s cut, held to its kinds' cap. Pair it with {@link #markLanded} on the instance it lands as, so
     * the watch does not change it again. {@code seconds} unchanged when nothing rules it. Never throws.
     */
    public static float lengthAsLanded(@Nonnull ComponentAccessor<EntityStore> accessor,
            @Nonnull Ref<EntityStore> carrier, int effectIndex, @Nullable Ref<EntityStore> owner, float seconds) {
        try {
            return hasRule(effectIndex) ? changedLength(accessor, carrier, effectIndex, owner, seconds) : seconds;
        } catch (Throwable t) {
            warnOnce("changing an effect's length", t);
            return seconds;
        }
    }

    /** Record that {@code active} landed with {@code remaining} seconds already through the rules ({@link #lengthAsLanded}). */
    public static void markLanded(@Nonnull ActiveEntityEffect active, float remaining) {
        SEEN.put(active, remaining);
    }

    /**
     * Put the timed effect {@code effectId} on {@code target} for {@code seconds} (before the Duration rules) under
     * {@code overlap}, owned by {@code owner}, so the families' rules read the owner's stats as it lands (your roots
     * hold longer) and the carrier's cut: {@link #lengthAsLanded}, times {@code scale} (0 to 1, what a control that
     * shrinks on repeat keeps of it, so the owner's added seconds shrink with the rest), then {@link #markLanded}, as
     * {@code DamageOverTime} lands one. A null owner, or the target itself, leaves it unowned: none of anyone's stats
     * lengthens it (a step that spares a boss or an elite passes none). World thread; never throws.
     *
     * @return true when it landed; false for an unknown or infinite effect, a target with no effect controller, a
     *         refusal by the effect's own {@code ApplyConditions} (an immunity), a non-positive length, or a throw
     */
    public static boolean landOwned(@Nonnull ComponentAccessor<EntityStore> accessor,
            @Nonnull Ref<EntityStore> target, @Nullable String effectId, float seconds,
            @Nonnull OverlapBehavior overlap, @Nullable Ref<EntityStore> owner, float scale) {
        try {
            if (effectId == null || effectId.isBlank() || !target.isValid() || !(seconds > 0f)) {
                return false;
            }
            int index = EntityEffect.getAssetMap().getIndex(effectId);
            EntityEffect effect = index == Integer.MIN_VALUE ? null : EntityEffect.getAssetMap().getAsset(index);
            if (effect == null || effect.isInfinite()) {
                return false;
            }
            EffectControllerComponent ctrl = accessor.getComponent(target, EffectControllerComponent.getComponentType());
            if (ctrl == null) {
                return false;
            }
            Ref<EntityStore> credit = owner != null && owner.isValid() && !owner.equals(target) ? owner : null;
            float kept = Math.max(0f, Math.min(1f, scale));
            float lasting = lengthAsLanded(accessor, target, index, credit, seconds) * kept;
            if (!(lasting > 0f) || !ctrl.addEffect(target, index, effect, lasting, overlap, accessor, credit)) {
                return false;
            }
            ActiveEntityEffect active = ctrl.getActiveEffects().get(index);
            if (active != null) {
                markLanded(active, active.getRemainingDuration());
            }
            EffectLandings.watch(target, System.nanoTime());
            return true;
        } catch (Throwable t) {
            warnOnce("landing an owned effect", t);
            return false;
        }
    }

    /** {@link #landOwned(ComponentAccessor, Ref, String, float, OverlapBehavior, Ref, float)} kept whole. */
    public static boolean landOwned(@Nonnull ComponentAccessor<EntityStore> accessor,
            @Nonnull Ref<EntityStore> target, @Nullable String effectId, float seconds,
            @Nonnull OverlapBehavior overlap, @Nullable Ref<EntityStore> owner) {
        return landOwned(accessor, target, effectId, seconds, overlap, owner, 1f);
    }

    /**
     * The length after the rules: {@code remaining} plus {@code addedSeconds}, then less {@code cutPercent} (held to
     * [0, {@code cutCap}]) of that, never below a tenth of a second, and never past {@code maxSeconds} when that is
     * above 0. Pure.
     */
    public static double lasting(double remaining, double addedSeconds, double cutPercent, double cutCap,
            double maxSeconds) {
        double longer = remaining + (Double.isFinite(addedSeconds) ? Math.max(0.0, addedSeconds) : 0.0);
        double cut = Double.isFinite(cutPercent) ? Math.max(0.0, Math.min(Math.max(0.0, cutCap), cutPercent)) : 0.0;
        double held = Math.max(0.1, longer * (1.0 - cut / 100.0));
        return maxSeconds > 0.0 && Double.isFinite(maxSeconds) ? Math.min(held, Math.max(0.1, maxSeconds)) : held;
    }

    /** Does anything rule the effect at {@code index}'s length: a family's Duration rule, or a kind's cap? */
    private static boolean hasRule(int index) {
        return !EffectFamilies.durationRules(index).isEmpty()
                || (EffectFamilies.controlKindMask(index) & ControlKinds.cappedMask()) != 0;
    }

    /**
     * {@link #lasting}, for a fixed control ({@code fixed}) dropping the owner's added seconds when
     * {@code skipsAddedSeconds} and the carrier's percent off when {@code skipsResist}; a kind's cap holds either way.
     * Pure.
     */
    public static double lasting(double remaining, double addedSeconds, double cutPercent, double cutCap,
            double maxSeconds, boolean fixed, boolean skipsAddedSeconds, boolean skipsResist) {
        return lasting(remaining, fixed && skipsAddedSeconds ? 0.0 : addedSeconds,
                fixed && skipsResist ? 0.0 : cutPercent, cutCap, maxSeconds);
    }

    /** {@code seconds} through the effect at {@code index}'s rules, as {@link #lasting} reads them. */
    private static float changedLength(@Nonnull ComponentAccessor<EntityStore> accessor,
            @Nonnull Ref<EntityStore> carrier, int index, @Nullable Ref<EntityStore> owner, float seconds) {
        List<EffectFamilies.DurationRule> rules = EffectFamilies.durationRules(index);
        return (float) lasting(seconds, secondsAdded(accessor, owner, rules), percentCut(accessor, carrier, rules),
                cap(rules), ControlKinds.maxSeconds(EffectFamilies.controlKindMask(index)),
                EffectFamilies.isFixed(index), ControlKinds.fixedSkipsAddedSeconds(), ControlKinds.fixedSkipsResist());
    }

    /** The owner's added seconds: each rule's {@code AddedBy} stat, each stat counted once. */
    private static double secondsAdded(@Nonnull ComponentAccessor<EntityStore> accessor,
            @Nullable Ref<EntityStore> owner, @Nonnull List<EffectFamilies.DurationRule> rules) {
        if (owner == null || !owner.isValid() || rules.isEmpty()) {
            return 0.0;
        }
        EntityStatMap stats = accessor.getComponent(owner, EntityStatMap.getComponentType());
        if (stats == null) {
            return 0.0;
        }
        double sum = 0.0;
        List<Integer> counted = new ArrayList<>();
        for (EffectFamilies.DurationRule rule : rules) {
            sum += readOnce(stats, rule.addedBy(), counted);
        }
        return sum;
    }

    /** The carrier's percent off: each rule's {@code CutBy} stat, each stat counted once. */
    private static double percentCut(@Nonnull ComponentAccessor<EntityStore> accessor, @Nonnull Ref<EntityStore> ref,
            @Nonnull List<EffectFamilies.DurationRule> rules) {
        if (rules.isEmpty()) {
            return 0.0;
        }
        EntityStatMap stats = accessor.getComponent(ref, EntityStatMap.getComponentType());
        if (stats == null) {
            return 0.0;
        }
        double sum = 0.0;
        List<Integer> counted = new ArrayList<>();
        for (EffectFamilies.DurationRule rule : rules) {
            sum += readOnce(stats, rule.cutBy(), counted);
        }
        return sum;
    }

    /** The lowest cap among the rules that cut (the strictest family wins). */
    private static double cap(@Nonnull List<EffectFamilies.DurationRule> rules) {
        double cap = 99.0;
        for (EffectFamilies.DurationRule rule : rules) {
            if (rule.cutBy() != null) {
                cap = Math.min(cap, rule.cutCap());
            }
        }
        return cap;
    }

    /**
     * {@code statId}'s maximum (a family file's stat, so a retired id reads its one successor,
     * {@code StatIndexCache.resolveCurrent}), 0 for none, or for a stat already in {@code counted}, which it joins: a
     * rule naming a renamed id and one naming its successor count it once.
     */
    private static double readOnce(@Nonnull EntityStatMap stats, @Nullable String statId,
            @Nonnull List<Integer> counted) {
        int index = statId == null ? StatIndexCache.UNRESOLVED : StatIndexCache.resolveCurrent(statId);
        if (index == StatIndexCache.UNRESOLVED || counted.contains(index)) {
            return 0.0;
        }
        counted.add(index);
        EntityStatValue value = stats.get(index);
        double max = value == null ? 0.0 : value.getMax();
        return Double.isFinite(max) ? max : 0.0;
    }

    private static void warnOnce(@Nonnull String what, @Nonnull Throwable t) {
        if (!failureWarned) {
            failureWarned = true;
            SafeLog.warn("[duration] " + what + " failed (named once): " + t);
        }
    }
}
