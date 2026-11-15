package com.ziggfreed.common.rune;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.plugin.PluginBase;

/**
 * The rune lines' setup: the per-player socket record ({@link RuneSockets}), registered before any world loads so a
 * save carrying it reads back, and its connect hook. Nothing else registers: a line opens only when a mod opens one
 * ({@link RuneBench}).
 */
public final class RuneBootstrap {

    private RuneBootstrap() {
    }

    /** Once, from the wiring root's setup. */
    public static void install(@Nonnull PluginBase plugin) {
        RuneSockets.register(plugin.getEntityStoreRegistry());
        RuneSockets.install(plugin);
    }
}
