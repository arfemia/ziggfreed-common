package com.ziggfreed.common.playerstats;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.plugin.PluginBase;

/**
 * Registers the player stat store at library setup, called once from the wiring root's
 * {@code setup()}: the per-player record and its connect hook, before any world loads (a component
 * type registered later cannot be read off entities saved carrying it). Registration only
 * ({@code RootRegistrationOnlyTest}).
 */
public final class StatsBootstrap {

    /** The owner every registration here is attributed to: the library itself. */
    public static final String OWNER = "ziggfreedcommon";

    private StatsBootstrap() {
    }

    /** Everything the stat store registers, in order. */
    public static void install(@Nonnull PluginBase plugin) {
        PlayerStatsComponent.register(plugin.getEntityStoreRegistry());
        PlayerStatsComponent.install(plugin);
    }
}
