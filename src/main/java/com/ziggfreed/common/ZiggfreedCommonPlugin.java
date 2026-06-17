package com.ziggfreed.common;

import javax.annotation.Nonnull;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;

/**
 * Entry point for Ziggfreed Common, a shared, mod-agnostic Hytale utility mod.
 *
 * <p>It ships a small set of stateless, config-free primitives ({@code sound/},
 * {@code camera/}, {@code util/}, {@code feedback/}, {@code ui/}) lifted from the
 * MMO Skill Tree mod and the Kweebec Nightmare minigame so that standalone
 * Ziggfreed minigames (and eventually the MMO) can consume one battle-tested
 * implementation instead of re-deriving it per mod. There is NO MMO config, no
 * per-player component, and no Perfect Utils coupling here - the only dependency
 * is the Hytale server jar.
 *
 * <p>The plugin itself registers nothing: every primitive is a static utility a
 * consumer calls directly. {@link #setup()} / {@link #shutdown()} exist only to
 * satisfy the {@link JavaPlugin} contract and to log presence so a missing jar is
 * obvious in the server log. {@link #LOGGER} mirrors the Kweebec plugin's logger
 * field so the lifted primitives can swap their logging target one-for-one.
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
        // Stateless static utils only; nothing to register. Presence log so a missing
        // jar (a consumer mod fails to find these classes) is obvious in the log.
        LOGGER.atInfo().log("ZiggfreedCommon setup complete (shared primitives available).");
    }

    @Override
    protected void shutdown() {
        LOGGER.atInfo().log("ZiggfreedCommon shutdown complete.");
    }
}
