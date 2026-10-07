package com.ziggfreed.common.instance;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.ziggfreed.common.encounter.asset.EncounterOwnerLayers;
import com.ziggfreed.common.instance.leaderboard.EncounterLeaderboardListener;
import com.ziggfreed.common.instance.leaderboard.RecordsDestinations;
import com.ziggfreed.common.instance.leaderboard.command.ZigLeaderboardCommand;
import com.ziggfreed.common.ui.menu.MenuSlot;
import com.ziggfreed.common.ui.menu.ZigMenu;
import com.ziggfreed.common.util.SafeLog;

/**
 * The instance module's registration phases, each called as one line from the wiring root's
 * {@code setup()}. Registration only; every decision lives in the module behind it.
 */
public final class InstanceBootstrap {

    private InstanceBootstrap() {
    }

    /**
     * Open the encounter leaderboard beside the library's owner files ({@code mods/ziggfreedcommon/},
     * the encounter owner files' folder) and hang its listener on the shared bus, so a boss defeat
     * writes its rows on every server running this library, with no consumer board needed; and
     * register {@code /zigleaderboard}, which reads those rows back as a fight's records screen for
     * any player. The listener is this module's one edge to the encounter framework that listens; the
     * screen only reads. Each guarded on its own.
     */
    public static void installEncounterLeaderboard(@Nonnull JavaPlugin plugin) {
        try {
            // Before 2.2.0 the board sat in the library's data folder; the first load that finds it
            // only there moves it across.
            EncounterLeaderboardListener.install(plugin, EncounterOwnerLayers.directory(), plugin.getDataDirectory());
        } catch (Throwable t) {
            SafeLog.warn("[encounter] the leaderboard listener could not be installed", t);
        }
        try {
            plugin.getCommandRegistry().registerCommand(new ZigLeaderboardCommand());
        } catch (Throwable t) {
            SafeLog.warn("[encounter] /zigleaderboard could not be registered", t);
        }
    }

    /**
     * The Records destination (before any asset decodes) and its tab in the shared menu. Registration only.
     */
    public static void registerRecords() {
        try {
            RecordsDestinations.register();
            ZigMenu.fill(MenuSlot.RECORDS, RecordsDestinations.entry());
        } catch (Throwable t) {
            SafeLog.warn("[encounter] the Records destination and menu tab could not be registered", t);
        }
    }

    /**
     * Write the encounter board's rows still waiting on their debounce, so a defeat in the last
     * seconds before a stop is kept. Called from the library's {@code shutdown()}, which the server
     * runs after it has disconnected every player and shut every world down, so no fight ends after
     * it. A stop with nothing new writes nothing, and it never throws.
     */
    public static void shutdown() {
        try {
            EncounterLeaderboardListener.flushNow();
        } catch (Throwable t) {
            SafeLog.warn("[encounter] the leaderboard could not be written at shutdown", t);
        }
    }
}
