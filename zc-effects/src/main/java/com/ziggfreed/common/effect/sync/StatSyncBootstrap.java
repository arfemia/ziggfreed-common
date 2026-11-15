package com.ziggfreed.common.effect.sync;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.plugin.PluginBase;

/**
 * Registers the one stat-to-effect pass ({@link StatEffectSync}) at plugin {@code setup()}, after zc-entity's
 * conditional holder pass (its ordering dependency, which must be registered first); the families register their
 * channels on it from their own bootstraps, in any order. Registration only. A failed registration rethrows (R700):
 * every such stat would stop reaching the engine.
 */
public final class StatSyncBootstrap {

    private StatSyncBootstrap() {
    }

    /** Call once from plugin {@code setup()}, after {@code ConditionBootstrap.install}. */
    public static void install(@Nonnull PluginBase plugin) {
        try {
            plugin.getEntityStoreRegistry().registerSystem(new StatEffectSync());
        } catch (Throwable t) {
            throw new IllegalStateException("[stat-sync] could not register the stat-to-effect pass", t);
        }
    }
}
