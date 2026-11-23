package com.ziggfreed.common.effect.landing;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.plugin.PluginBase;

/**
 * Registers the one landing watch ({@link EffectLandings}): the carrier pass, the watch a hit puts on its target and
 * the forgetting of an entity that left. Its readers register themselves from their own bootstraps (the effect-length
 * pass, zc-track's effect moments). Registration only. A failed registration rethrows (R700): every effect's length
 * rule and every effect moment would go quiet.
 */
public final class LandingBootstrap {

    private LandingBootstrap() {
    }

    /** Call once from plugin {@code setup()}. */
    public static void install(@Nonnull PluginBase plugin) {
        try {
            plugin.getEntityStoreRegistry().registerSystem(new EffectLandingSystems.Carriers());
            plugin.getEntityStoreRegistry().registerSystem(new EffectLandingSystems.Watch());
            plugin.getEntityStoreRegistry().registerSystem(new EffectLandingSystems.Left());
        } catch (Throwable t) {
            throw new IllegalStateException("[landing] could not register the effect landing watch", t);
        }
    }
}
