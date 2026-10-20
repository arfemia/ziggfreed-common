package com.ziggfreed.common.effect.duration;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

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
import com.ziggfreed.common.stats.StatIndexCache;
import com.ziggfreed.common.stats.condition.EffectFamilies;
import com.ziggfreed.common.util.SafeLog;

/**
 * How long an effect lasts as it lands, by stats (R757): an effect family's {@code Duration} rule
 * ({@code EffectFamilyAsset.Duration}) names a stat of whoever put the effect on, each point a second more
 * ({@code EffectDuration_Dot}: your poisons last longer), and one of whoever carries it, each point a percent off
 * held to the family's cap ({@code EffectTaken_Control}: stuns on you end sooner).
 *
 * <p><b>When.</b> The engine has no moment for an effect landing, so this reads an effect the first time it is seen
 * active, and again whenever its remaining time has grown since (a re-application that refreshed it), and changes its
 * remaining time once through the engine's own {@code addEffect} with the owner kept. Only entities that can carry
 * such an effect are read: every living player each tick, and every entity a player's hit landed on in the last
 * {@value #WATCH_SECONDS} seconds ({@link #watch}), so a creature no player touched costs nothing.
 *
 * <p>What was seen is kept per active effect object in a weak table, so a removed effect drops out on its own.
 * Transient and single-process; world thread only; never throws.
 */
public final class EffectDurations {

    /** How long a hit target stays read, in seconds: long enough for a hit's own effects to land. */
    public static final double WATCH_SECONDS = 2.0;

    /** A change in remaining time smaller than this is not a refresh. */
    private static final float EPSILON = 0.05f;

    /** Each watched entity and when its watch ends (nanos). */
    private static final Map<Ref<EntityStore>, Long> WATCHED = new ConcurrentHashMap<>();

    /** Each active effect seen, and its remaining time as last left (seconds). */
    private static final Map<ActiveEntityEffect, Float> SEEN = Collections.synchronizedMap(new WeakHashMap<>());

    private static volatile boolean failureWarned;

    private EffectDurations() {
    }

    /** Read {@code ref} for the next {@value #WATCH_SECONDS} seconds: a player's hit just landed on it. */
    public static void watch(@Nonnull Ref<EntityStore> ref, long nanos) {
        WATCHED.put(ref, nanos + (long) (WATCH_SECONDS * 1_000_000_000L));
    }

    /** The watched entities of {@code store} whose watch is still on; expired or invalid ones are dropped. */
    @Nonnull
    static List<Ref<EntityStore>> watchedIn(@Nonnull Store<EntityStore> store, long nanos) {
        if (WATCHED.isEmpty()) {
            return List.of();
        }
        List<Ref<EntityStore>> out = new ArrayList<>();
        WATCHED.entrySet().removeIf(entry -> !entry.getKey().isValid() || entry.getValue() < nanos);
        for (Ref<EntityStore> ref : WATCHED.keySet()) {
            if (ref.getStore() == store) {
                out.add(ref);
            }
        }
        return out;
    }

    /** Forget {@code ref} (it left its store). */
    static void forget(@Nonnull Ref<EntityStore> ref) {
        WATCHED.remove(ref);
    }

    /**
     * Read every active effect of {@code ref} that landed or was refreshed since it was last read, and change its
     * remaining time by its families' rules. Never throws.
     */
    public static void adjust(@Nonnull ComponentAccessor<EntityStore> accessor, @Nonnull Ref<EntityStore> ref) {
        try {
            if (!ref.isValid()) {
                return;
            }
            EffectControllerComponent ctrl = accessor.getComponent(ref, EffectControllerComponent.getComponentType());
            if (ctrl == null || ctrl.getActiveEffects().isEmpty() || !EffectFamilies.anyDurationRule()) {
                return;
            }
            for (ActiveEntityEffect active : List.copyOf(ctrl.getActiveEffects().values())) {
                if (active.isInfinite()) {
                    continue;
                }
                float remaining = active.getRemainingDuration();
                Float last = SEEN.get(active);
                SEEN.put(active, remaining);
                if (last != null && remaining <= last + EPSILON) {
                    continue; // seen, and only ticking down since
                }
                List<EffectFamilies.DurationRule> rules = EffectFamilies.durationRules(active.getEntityEffectIndex());
                if (rules.isEmpty()) {
                    continue;
                }
                float changed = (float) changedRemaining(remaining, secondsAdded(accessor, active.getOwner(), rules),
                        percentCut(accessor, ref, rules), cap(rules));
                if (Math.abs(changed - remaining) < EPSILON) {
                    continue;
                }
                EntityEffect effect = EntityEffect.getAssetMap().getAsset(active.getEntityEffectIndex());
                if (effect == null) {
                    continue;
                }
                ctrl.addEffect(ref, active.getEntityEffectIndex(), effect, changed, OverlapBehavior.OVERWRITE,
                        accessor, active.getOwner());
                SEEN.put(active, changed);
            }
        } catch (Throwable t) {
            if (!failureWarned) {
                failureWarned = true;
                SafeLog.warn("[duration] changing an effect's length failed (named once): " + t);
            }
        }
    }

    /**
     * The remaining time after the stats: {@code remaining} plus {@code seconds}, then less {@code cutPercent}
     * (held to [0, {@code cap}]) of that, never below a tenth of a second. Pure.
     */
    static double changedRemaining(double remaining, double seconds, double cutPercent, double cap) {
        double longer = remaining + (Double.isFinite(seconds) ? Math.max(0.0, seconds) : 0.0);
        double cut = Double.isFinite(cutPercent) ? Math.max(0.0, Math.min(Math.max(0.0, cap), cutPercent)) : 0.0;
        return Math.max(0.1, longer * (1.0 - cut / 100.0));
    }

    /** The owner's added seconds: each rule's {@code AddedBy} stat, each stat counted once. */
    private static double secondsAdded(@Nonnull ComponentAccessor<EntityStore> accessor,
            @Nullable Ref<EntityStore> owner, @Nonnull List<EffectFamilies.DurationRule> rules) {
        if (owner == null || !owner.isValid()) {
            return 0.0;
        }
        EntityStatMap stats = accessor.getComponent(owner, EntityStatMap.getComponentType());
        if (stats == null) {
            return 0.0;
        }
        double sum = 0.0;
        List<String> counted = new ArrayList<>();
        for (EffectFamilies.DurationRule rule : rules) {
            String stat = rule.addedBy();
            if (stat != null && !counted.contains(stat.toLowerCase(Locale.ROOT))) {
                counted.add(stat.toLowerCase(Locale.ROOT));
                sum += readMax(stats, stat);
            }
        }
        return sum;
    }

    /** The carrier's percent off: each rule's {@code CutBy} stat, each stat counted once. */
    private static double percentCut(@Nonnull ComponentAccessor<EntityStore> accessor, @Nonnull Ref<EntityStore> ref,
            @Nonnull List<EffectFamilies.DurationRule> rules) {
        EntityStatMap stats = accessor.getComponent(ref, EntityStatMap.getComponentType());
        if (stats == null) {
            return 0.0;
        }
        double sum = 0.0;
        List<String> counted = new ArrayList<>();
        for (EffectFamilies.DurationRule rule : rules) {
            String stat = rule.cutBy();
            if (stat != null && !counted.contains(stat.toLowerCase(Locale.ROOT))) {
                counted.add(stat.toLowerCase(Locale.ROOT));
                sum += readMax(stats, stat);
            }
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

    private static double readMax(@Nonnull EntityStatMap stats, @Nonnull String statId) {
        int index = StatIndexCache.resolve(statId);
        if (index == StatIndexCache.UNRESOLVED) {
            return 0.0;
        }
        EntityStatValue value = stats.get(index);
        double max = value == null ? 0.0 : value.getMax();
        return Double.isFinite(max) ? max : 0.0;
    }
}
