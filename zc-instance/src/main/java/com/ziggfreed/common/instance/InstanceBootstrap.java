package com.ziggfreed.common.instance;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.ziggfreed.common.encounter.asset.EncounterOwnerLayers;
import com.ziggfreed.common.instance.leaderboard.EncounterLeaderboardListener;
import com.ziggfreed.common.instance.leaderboard.RecordsDestinations;
import com.ziggfreed.common.instance.leaderboard.command.ZigLeaderboardCommand;
import com.ziggfreed.common.instance.leaderboard.statboard.StatBoard;
import com.ziggfreed.common.instance.leaderboard.statboard.StatBoardRecords;
import com.ziggfreed.common.instance.leaderboard.statboard.StatPeriodOwnerLayers;
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
     * Open the frozen encounter leaderboard beside the library's owner files ({@code mods/ziggfreedcommon/},
     * the encounter owner files' folder), which nothing writes any more: the stat store imports its rows
     * once and keeps a fight's records on the stat board; and register {@code /zigleaderboard}, which opens
     * Records' Boss Fights for any player. Each guarded on its own.
     */
    public static void installEncounterLeaderboard(@Nonnull JavaPlugin plugin) {
        try {
            // Before 2.2.0 the board sat in the library's data folder; the first load that finds it
            // only there moves it across.
            EncounterLeaderboardListener.open(EncounterOwnerLayers.directory(), plugin.getDataDirectory());
        } catch (Throwable t) {
            SafeLog.warn("[encounter] the frozen encounter leaderboard could not be opened", t);
        }
        try {
            plugin.getCommandRegistry().registerCommand(new ZigLeaderboardCommand());
        } catch (Throwable t) {
            SafeLog.warn("[encounter] /zigleaderboard could not be registered", t);
        }
    }

    /**
     * Open the stat board ({@code stats-board.json}) beside the library's owner files and put it on
     * Records. It shows nothing until the store that keeps the stats fills its source
     * ({@code StatBoard.setSource}), so until then Records shows no tab.
     */
    public static void installStatBoard() {
        try {
            StatBoard.install(StatPeriodOwnerLayers.directory());
            RecordsDestinations.setBoard(StatBoardRecords.INSTANCE);
        } catch (Throwable t) {
            SafeLog.warn("[statboard] the stat board could not be opened", t);
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
     * Write the boards' rows still waiting on their debounce, so a stat counted in the last seconds
     * before a stop is kept. Called from the library's {@code shutdown()}, which the server runs after it
     * has disconnected every player and shut every world down, so nothing is counted after it. A stop
     * with nothing new writes nothing, and it never throws.
     */
    public static void shutdown() {
        try {
            EncounterLeaderboardListener.flushNow();
        } catch (Throwable t) {
            SafeLog.warn("[encounter] the leaderboard could not be written at shutdown", t);
        }
        try {
            StatBoard.flushNow();
        } catch (Throwable t) {
            SafeLog.warn("[statboard] the stat board could not be written at shutdown", t);
        }
    }
}
