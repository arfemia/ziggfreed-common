package com.ziggfreed.common.effect.duration;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.plugin.PluginBase;
import com.ziggfreed.common.effect.landing.EffectLandings;

/**
 * Registers how long an effect lasts as it lands, by stats ({@link EffectDurations}): the length pass as a reader of
 * the one landing watch (zc-effects' {@code effect/landing/}, which the wiring root installs beside this). Registration
 * only. Nothing to wire for a consumer: the rules are effect families' {@code Duration} groups and the kinds of
 * control's caps, data any pack writes.
 */
public final class DurationBootstrap {

    private DurationBootstrap() {
    }

    /** Call once from plugin {@code setup()}. */
    public static void install(@Nonnull PluginBase plugin) {
        EffectLandings.register(EffectDurations.WATCHER);
    }
}
