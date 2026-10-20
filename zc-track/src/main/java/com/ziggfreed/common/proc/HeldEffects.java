package com.ziggfreed.common.proc;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.annotation.Nonnull;

import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.effect.NativeEffectUtil;
import com.ziggfreed.common.factor.FactorContext;
import com.ziggfreed.common.stats.condition.Nearby;

/**
 * The runtime of held effects ({@link HeldEffectAsset}): on the holder pass, every effect a file names is put on,
 * with no end, while any live file naming it holds, and taken off the first pass none does. Only an effect worn with no
 * end is taken off, so a timed apply of the same effect by something else runs out on its own. World thread.
 */
final class HeldEffects {

    private HeldEffects() {
    }

    /** Reconcile every held effect on {@code holder}. */
    static void pass(@Nonnull ComponentAccessor<EntityStore> accessor, @Nonnull Ref<EntityStore> holder,
            @Nonnull FactorContext context, @Nonnull Instant now) {
        List<HeldEffectAsset> files = HeldEffectConfig.getInstance().held();
        if (files.isEmpty()) {
            return;
        }
        Map<String, Boolean> wanted = new LinkedHashMap<>();
        Map<String, String> spelled = new LinkedHashMap<>();
        for (HeldEffectAsset file : files) {
            String effect = file.effect();
            if (effect == null) {
                continue;
            }
            String key = effect.toLowerCase(Locale.ROOT);
            spelled.putIfAbsent(key, effect);
            boolean holds = file.isEnabled() && holds(accessor, holder, file, context, now);
            wanted.merge(key, holds, Boolean::logicalOr);
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

    /** Does the file's hold stand for {@code holder}: its stat above 0, its conditions, its Requires? */
    private static boolean holds(@Nonnull ComponentAccessor<EntityStore> accessor, @Nonnull Ref<EntityStore> holder,
            @Nonnull HeldEffectAsset file, @Nonnull FactorContext context, @Nonnull Instant now) {
        String stat = file.stat();
        if (stat != null && !(Procs.statPoints(accessor, holder, stat) > 0.0)) {
            return false;
        }
        if (!Nearby.allHold(accessor, holder, file.holderConditions(), now)) {
            return false;
        }
        return Procs.gatePasses(file.liveRequires(), context);
    }
}
