package com.ziggfreed.common.proc;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.joml.Vector3d;

import com.hypixel.hytale.assetstore.map.AssetMapWithIndexes;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.EntityEffect;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.OverlapBehavior;
import com.hypixel.hytale.server.core.entity.effect.ActiveEntityEffect;
import com.hypixel.hytale.server.core.entity.effect.EffectControllerComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.effect.NativeEffectUtil;
import com.ziggfreed.common.effect.control.AggroControl;
import com.ziggfreed.common.factor.FactorContext;
import com.ziggfreed.common.factor.FactorFormula;
import com.ziggfreed.common.stats.condition.Nearby;

/**
 * The runtime of held effects ({@link HeldEffectAsset}): on the holder pass, every effect a file names is put on,
 * with no end, while any live file naming it is on, and taken off the first pass none is. A file is on while its hold
 * stands, once it has stood its {@code Hold} seconds, and for its {@code Linger} seconds after it stops (each file
 * keeps its own times, per holder, never saved). Only an effect worn with no end is taken off, so a timed apply of the
 * same effect by something else runs out on its own. World thread; a holder's times are its world's thread's alone.
 *
 * <p><b>Auras.</b> A file with {@code Reach} also puts its effect on the others in reach while it is on (the holder's
 * allies, the creatures their taunt holds), each for {@value #REACH_SECONDS} seconds at a time with the holder as its
 * owner, topped up while it has under {@value #REFRESH_BELOW} seconds left. Nothing keeps a record of who wears it, so
 * nothing goes stale: whoever steps out of reach, a hold that ends, a holder that dies, leaves or changes worlds, each
 * leaves the effect to run out on its own within that time. One that already wears it with no end (their own held
 * effect) is left alone. Bounded: one pass reaches at most {@code Max} (never past 32) within {@code Radius} (never
 * past 64), four times a second.
 */
final class HeldEffects {

    /** How long one aura apply lasts on another. */
    static final float REACH_SECONDS = 1.5f;

    /** An aura on another is topped up when it has less than this left. */
    static final float REFRESH_BELOW = 0.8f;

    /** One file's times on one holder: since when its hold has stood (-1: not standing), since when it stopped. */
    static final class Times {
        long standingSince = -1L;
        long stoppedAt = -1L;
        boolean on;
    }

    private static final Map<Ref<EntityStore>, Map<String, Times>> TIMES = new ConcurrentHashMap<>();

    private HeldEffects() {
    }

    /** Reconcile every held effect on {@code holder}, and reach the others its auras reach. */
    static void pass(@Nonnull ComponentAccessor<EntityStore> accessor, @Nonnull Ref<EntityStore> holder,
            @Nonnull FactorContext context, @Nonnull Instant now, long nanos) {
        List<HeldEffectAsset> files = HeldEffectConfig.getInstance().held();
        if (files.isEmpty()) {
            return;
        }
        Map<String, Times> times = TIMES.computeIfAbsent(holder, h -> new HashMap<>());
        Map<String, Boolean> wanted = new LinkedHashMap<>();
        Map<String, String> spelled = new LinkedHashMap<>();
        for (HeldEffectAsset file : files) {
            String effect = file.effect();
            if (effect == null) {
                continue;
            }
            String key = effect.toLowerCase(Locale.ROOT);
            spelled.putIfAbsent(key, effect);
            HeldEffectAsset.Reach reach = file.reach();
            List<Ref<EntityStore>> reached = new ArrayList<>();
            boolean on = on(accessor, holder, file, context, now, nanos, times, reached);
            wanted.merge(key, on && (reach == null || reach.self()), Boolean::logicalOr);
            if (on && reach != null) {
                for (Ref<EntityStore> other : reached) {
                    topUp(accessor, other, effect, holder);
                }
            }
        }
        for (Map.Entry<String, Boolean> entry : wanted.entrySet()) {
            String effect = spelled.get(entry.getKey());
            Float remaining = NativeEffectUtil.remaining(accessor, holder, effect);
            boolean worn = remaining != null && remaining.isInfinite();
            if (entry.getValue() && !worn) {
                NativeEffectUtil.applyInfinite(accessor, holder, effect);
            } else if (!entry.getValue() && worn) {
                NativeEffectUtil.remove(accessor, holder, effect);
            }
        }
    }

    /**
     * Is {@code file} on for {@code holder} this pass, its Hold and Linger read? With {@code Reach}, the others it
     * reaches this pass go into {@code reached} (its {@code Min} of them a part of the hold standing).
     */
    private static boolean on(@Nonnull ComponentAccessor<EntityStore> accessor, @Nonnull Ref<EntityStore> holder,
            @Nonnull HeldEffectAsset file, @Nonnull FactorContext context, @Nonnull Instant now, long nanos,
            @Nonnull Map<String, Times> times, @Nonnull List<Ref<EntityStore>> reached) {
        String key = file.getId() == null ? "" : file.getId().toLowerCase(Locale.ROOT);
        if (!file.isEnabled() || !Procs.gatePasses(file.liveRequires(), context)) {
            times.remove(key);
            return false;
        }
        boolean standing = holds(accessor, holder, file, now);
        HeldEffectAsset.Reach reach = file.reach();
        // A hold that does not stand, with no linger to keep the file on, reaches no one: skip the look around.
        if (reach != null && (standing || file.linger() != null)) {
            reached.addAll(reach(accessor, context, holder, reach, now));
            standing &= reached.size() >= reach.min();
        }
        if (file.hold() == null && file.linger() == null) {
            times.remove(key);
            return standing;
        }
        Times state = times.computeIfAbsent(key, k -> new Times());
        return step(state, standing, nanos, seconds(file.hold(), context), seconds(file.linger(), context));
    }

    /**
     * One look at a file's times: its hold {@code standing} or not at {@code nanos}, with a hold of
     * {@code holdNanos} before it goes on and a linger of {@code lingerNanos} after it stops. Answers whether it is on,
     * and keeps the times for the next look. Pure.
     */
    static boolean step(@Nonnull Times state, boolean standing, long nanos, long holdNanos, long lingerNanos) {
        if (standing) {
            state.stoppedAt = -1L;
            if (state.standingSince < 0L) {
                state.standingSince = nanos;
            }
            if (!state.on && nanos - state.standingSince >= holdNanos) {
                state.on = true;
            }
            return state.on;
        }
        state.standingSince = -1L;
        if (!state.on) {
            return false;
        }
        if (state.stoppedAt < 0L) {
            state.stoppedAt = nanos;
        }
        if (nanos - state.stoppedAt >= lingerNanos) {
            state.on = false;
            state.stoppedAt = -1L;
        }
        return state.on;
    }

    /**
     * The others an aura reaches from {@code holder}: their allies and, or, the creatures their taunt holds, within
     * the radius and meeting the conditions, behind them when asked, allies first then nearest, at most {@code Max}.
     */
    @Nonnull
    private static List<Ref<EntityStore>> reach(@Nonnull ComponentAccessor<EntityStore> accessor,
            @Nonnull FactorContext context, @Nonnull Ref<EntityStore> holder, @Nonnull HeldEffectAsset.Reach reach,
            @Nonnull Instant now) {
        int max = Math.max(reach.max(), reach.min());
        Vector3d at = Nearby.positionOf(accessor, holder);
        if (max <= 0 || at == null || !(reach.radius() > 0.0) || reach.reachesNoKind()) {
            return List.of();
        }
        double[] look = reach.behind() ? ProcActions.levelLook(accessor, holder) : null;
        if (reach.behind() && look == null) {
            return List.of();
        }
        List<Ref<EntityStore>> out = new ArrayList<>();
        if (reach.allies()) {
            for (Ref<EntityStore> other : Nearby.alliesAround(accessor, holder, at, reach.radius(),
                    reach.conditions(), now, 0)) {
                if (look == null || behind(at, look, Nearby.positionOf(accessor, other))) {
                    out.add(other);
                    if (out.size() >= max) {
                        return out;
                    }
                }
            }
        }
        if (reach.taunted()) {
            for (Ref<EntityStore> foe : Nearby.foesAround(accessor, holder, at, reach.radius(), reach.conditions(),
                    now, 0)) {
                if (AggroControl.tauntedBy(accessor, holder, foe)
                        && (look == null || behind(at, look, Nearby.positionOf(accessor, foe)))) {
                    out.add(foe);
                    if (out.size() >= max) {
                        return out;
                    }
                }
            }
        }
        return out;
    }

    /**
     * Does one standing at {@code there} stand behind a holder at {@code at} looking along {@code look} (a level unit
     * {x, z})? Behind is the far side of the line across the holder's look. Pure.
     */
    static boolean behind(@Nonnull Vector3d at, @Nonnull double[] look, @Nullable Vector3d there) {
        if (there == null) {
            return false;
        }
        double dx = there.x - at.x;
        double dz = there.z - at.z;
        return dx * look[0] + dz * look[1] < 0.0;
    }

    /**
     * Put {@code effect} on {@code other} for {@value #REACH_SECONDS} seconds, owned by {@code holder}, unless it
     * already wears it with no end or with at least {@value #REFRESH_BELOW} seconds left.
     */
    private static void topUp(@Nonnull ComponentAccessor<EntityStore> accessor, @Nonnull Ref<EntityStore> other,
            @Nonnull String effectId, @Nonnull Ref<EntityStore> holder) {
        try {
            if (!other.isValid() || Nearby.same(other, holder)) {
                return;
            }
            int index = EntityEffect.getAssetMap().getIndex(effectId);
            EntityEffect effect = index == AssetMapWithIndexes.NOT_FOUND ? null
                    : EntityEffect.getAssetMap().getAsset(index);
            EffectControllerComponent controller = effect == null ? null
                    : accessor.getComponent(other, EffectControllerComponent.getComponentType());
            if (controller == null) {
                return;
            }
            ActiveEntityEffect active = controller.getActiveEffects().get(index);
            if (active != null && (active.isInfinite() || active.getRemainingDuration() >= REFRESH_BELOW)) {
                return;
            }
            controller.addEffect(other, index, effect, REACH_SECONDS, OverlapBehavior.OVERWRITE, accessor, holder);
        } catch (Throwable t) {
            Procs.failed("an aura reaching another", t);
        }
    }

    private static long seconds(@Nullable FactorFormula formula, @Nonnull FactorContext context) {
        if (formula == null) {
            return 0L;
        }
        double value = Procs.evaluate(formula, context);
        return value > 0.0 ? (long) (value * 1_000_000_000.0) : 0L;
    }

    /** Does the file's hold stand for {@code holder}: its stat above 0, its conditions? */
    private static boolean holds(@Nonnull ComponentAccessor<EntityStore> accessor, @Nonnull Ref<EntityStore> holder,
            @Nonnull HeldEffectAsset file, @Nonnull Instant now) {
        String stat = file.stat();
        if (stat != null && !(Procs.statPoints(accessor, holder, stat) > 0.0)) {
            return false;
        }
        return Nearby.allHold(accessor, holder, file.holderConditions(), now);
    }

    /** Forget a holder that left its store. */
    static void forget(@Nonnull Ref<EntityStore> ref) {
        TIMES.remove(ref);
    }
}
