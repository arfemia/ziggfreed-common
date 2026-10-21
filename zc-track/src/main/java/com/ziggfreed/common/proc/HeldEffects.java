package com.ziggfreed.common.proc;

import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.effect.NativeEffectUtil;
import com.ziggfreed.common.factor.FactorContext;
import com.ziggfreed.common.factor.FactorFormula;
import com.ziggfreed.common.stats.condition.Nearby;

/**
 * The runtime of held effects ({@link HeldEffectAsset}): on the holder pass, every effect a file names is put on,
 * with no end, while any live file naming it is on, and taken off the first pass none is. A file is on while its hold
 * stands, once it has stood its {@code Hold} seconds, and for its {@code Linger} seconds after it stops (each file
 * keeps its own times, per holder, never saved). Only an effect worn with no end is taken off, so a timed apply of the
 * same effect by something else runs out on its own. World thread; a holder's times are its world's thread's alone.
 */
final class HeldEffects {

    /** One file's times on one holder: since when its hold has stood (-1: not standing), since when it stopped. */
    static final class Times {
        long standingSince = -1L;
        long stoppedAt = -1L;
        boolean on;
    }

    private static final Map<Ref<EntityStore>, Map<String, Times>> TIMES = new ConcurrentHashMap<>();

    private HeldEffects() {
    }

    /** Reconcile every held effect on {@code holder}. */
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
            wanted.merge(key, on(accessor, holder, file, context, now, nanos, times), Boolean::logicalOr);
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

    /** Is {@code file} on for {@code holder} this pass, its Hold and Linger read? */
    private static boolean on(@Nonnull ComponentAccessor<EntityStore> accessor, @Nonnull Ref<EntityStore> holder,
            @Nonnull HeldEffectAsset file, @Nonnull FactorContext context, @Nonnull Instant now, long nanos,
            @Nonnull Map<String, Times> times) {
        String key = file.getId() == null ? "" : file.getId().toLowerCase(Locale.ROOT);
        if (!file.isEnabled() || !Procs.gatePasses(file.liveRequires(), context)) {
            times.remove(key);
            return false;
        }
        boolean standing = holds(accessor, holder, file, now);
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
