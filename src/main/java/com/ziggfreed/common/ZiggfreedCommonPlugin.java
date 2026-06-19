package com.ziggfreed.common;

import javax.annotation.Nonnull;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import com.ziggfreed.common.asset.FrameworkAssetRegistrar;

/**
 * Entry point for Ziggfreed Common, a shared, mod-agnostic Hytale utility mod.
 *
 * <p>It ships a set of stateless, config-free primitives ({@code sound/},
 * {@code camera/}, {@code util/}, {@code feedback/}, {@code ui/}) lifted from the
 * MMO Skill Tree mod and the Kweebec Nightmare minigame so that standalone
 * Ziggfreed minigames (and eventually the MMO) can consume one battle-tested
 * implementation instead of re-deriving it per mod, PLUS the reusable
 * instance-experience framework (dialogues, instance presets, leaderboard layout,
 * party settings, multi-phase bosses, banded effects, encounter rules, prefab
 * placements). There is NO MMO config, no per-player component, and no Perfect Utils
 * coupling here - the only dependency is the Hytale server jar.
 *
 * <p>The static primitives register nothing (a consumer calls them directly), but the
 * framework asset stores ARE registered here: {@link #setup()} calls
 * {@link FrameworkAssetRegistrar} so common OWNS each framework store at
 * {@code Server/ZiggfreedCommon/<Type>/} (one class = one registrant; a consumer must
 * NOT re-register them). A consumer authors JSON into those paths and reads the resolved
 * config back. {@link #LOGGER} mirrors the Kweebec plugin's logger field so the lifted
 * primitives can swap their logging target one-for-one.
 */
public class ZiggfreedCommonPlugin extends JavaPlugin {

    public static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    private static ZiggfreedCommonPlugin instance;

    @Nonnull
    public static ZiggfreedCommonPlugin getInstance() {
        return instance;
    }

    public ZiggfreedCommonPlugin(@Nonnull JavaPluginInit init) {
        super(init);
        instance = this;
        LOGGER.atInfo().log("ZiggfreedCommon initializing...");
    }

    @Override
    protected void setup() {
        // Register the framework asset stores ONCE (common owns them at
        // Server/ZiggfreedCommon/<Type>/). The stateless static primitives still
        // register nothing - a consumer calls them directly.
        FrameworkAssetRegistrar.registerAll(this);
        LOGGER.atInfo().log("ZiggfreedCommon setup complete (framework stores + shared primitives available).");
    }

    @Override
    protected void shutdown() {
        LOGGER.atInfo().log("ZiggfreedCommon shutdown complete.");
    }
}
