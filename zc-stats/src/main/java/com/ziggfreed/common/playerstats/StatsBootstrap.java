package com.ziggfreed.common.playerstats;

import javax.annotation.Nonnull;

import com.hypixel.hytale.event.EventPriority;
import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerReadyEvent;
import com.hypixel.hytale.server.core.plugin.PluginBase;
import com.ziggfreed.common.factor.CounterFactors;
import com.ziggfreed.common.instance.leaderboard.statboard.StatBoard;
import com.ziggfreed.common.playerstats.asset.StatValidator;
import com.ziggfreed.common.playerstats.board.StatBoardRankFeed;
import com.ziggfreed.common.playerstats.board.StatsBoardSource;
import com.ziggfreed.common.playerstats.command.ZigStatsCommand;
import com.ziggfreed.common.playerstats.system.PlayerDeathStatSystem;
import com.ziggfreed.common.playerstats.system.PlaytimeStatSystem;
import com.ziggfreed.common.progress.runtime.ProgressionRuntime;
import com.ziggfreed.common.util.SafeLog;

/**
 * Registers the player stat store at library setup, called once from the wiring root's
 * {@code setup()}: the per-player record and its connect hook, before any world loads (a component
 * type registered later cannot be read off entities saved carrying it); the owner's switch; the moment
 * counter at the library-default rank; the death and playtime systems and the playtime commit at
 * logout; the ready re-sync of the board; the library's default {@code ziggfreedcommon:counter}
 * source; the stat board's feed and source; and the {@code /zigstats} family. The stat file store itself is registered with the other
 * framework stores. Registration only ({@code RootRegistrationOnlyTest}).
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
        StatsSwitch.reload();
        registerVocabulary();
        try {
            plugin.getEntityStoreRegistry().registerSystem(new PlayerDeathStatSystem());
            plugin.getEntityStoreRegistry().registerSystem(new PlaytimeStatSystem());
            plugin.getEventRegistry().register(PlayerDisconnectEvent.class, PlaytimeStatSystem::onPlayerDisconnect);
            // NORMAL, and run as a world task: a consumer's FIRST-priority ready task (a one-time import
            // into this record) is queued ahead of it, so the re-sync reads the imported record.
            plugin.getEventRegistry().registerGlobal(EventPriority.NORMAL, PlayerReadyEvent.class,
                    StatsReadySync::onPlayerReady);
        } catch (Throwable t) {
            SafeLog.warn("[stats] the stat systems could not be registered", t);
        }
        try {
            plugin.getCommandRegistry().registerCommand(new ZigStatsCommand());
        } catch (Throwable t) {
            SafeLog.warn("[stats] the /zigstats command could not be registered", t);
        }
    }

    /**
     * The plugin-free half: the moment counter at the library-default rank, the library's default
     * counter source (R51), and the stat board: what is counted is ranked on it, the board asks this
     * store whether it runs and what each section line's column is, and the stat audit checks the
     * sections' lines. A consumer that keeps tallies of its own installs its source at its own setup,
     * after this one.
     */
    public static void registerVocabulary() {
        ProgressionRuntime.defaults(OWNER).momentListener(new StatsMomentListener());
        CounterFactors.source(new StatsCounterSource());
        PlayerStats.rankInto(new StatBoardRankFeed());
        StatBoard.setSource(new StatsBoardSource());
        StatValidator.sections(StatsBoardSource::sectionLines);
    }
}
