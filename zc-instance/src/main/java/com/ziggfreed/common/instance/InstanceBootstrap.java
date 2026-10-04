package com.ziggfreed.common.instance;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.ziggfreed.common.instance.leaderboard.EncounterLeaderboardListener;
import com.ziggfreed.common.instance.leaderboard.command.ZigLeaderboardCommand;
import com.ziggfreed.common.util.SafeLog;

/**
 * The instance module's registration phases, each called as one line from the wiring root's
 * {@code setup()}. Registration only; every decision lives in the module behind it.
 */
public final class InstanceBootstrap {

    private InstanceBootstrap() {
    }

    /**
     * Open the encounter leaderboard under the library's data directory and hang its listener on
     * the shared bus, so a boss defeat writes its rows on every server running this library, with
     * no consumer board needed; and register {@code /zigleaderboard}, which reads those rows back as
     * a fight's records screen for any player. The listener is this module's one edge to the
     * encounter framework that listens; the screen only reads. Each guarded on its own.
     */
    public static void installEncounterLeaderboard(@Nonnull JavaPlugin plugin) {
        try {
            EncounterLeaderboardListener.install(plugin, plugin.getDataDirectory());
        } catch (Throwable t) {
            SafeLog.warn("[encounter] the leaderboard listener could not be installed", t);
        }
        try {
            plugin.getCommandRegistry().registerCommand(new ZigLeaderboardCommand());
        } catch (Throwable t) {
            SafeLog.warn("[encounter] /zigleaderboard could not be registered", t);
        }
    }
}
