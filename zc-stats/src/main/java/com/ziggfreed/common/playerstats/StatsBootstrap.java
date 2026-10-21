package com.ziggfreed.common.playerstats;

import javax.annotation.Nonnull;

import com.hypixel.hytale.event.EventPriority;
import com.hypixel.hytale.server.core.event.events.BootEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerReadyEvent;
import com.hypixel.hytale.server.core.plugin.PluginBase;
import com.ziggfreed.common.counter.NativeReaders;
import com.ziggfreed.common.encounter.event.EncounterDefeatedEvent;
import com.ziggfreed.common.encounter.event.EncounterWipedEvent;
import com.ziggfreed.common.factor.CounterFactors;
import com.ziggfreed.common.instance.dungeon.DungeonRuns;
import com.ziggfreed.common.instance.dungeon.DungeonWavesEvent;
import com.ziggfreed.common.instance.leaderboard.statboard.StatBoard;
import com.ziggfreed.common.instance.metadata.InstanceRoundCompletedEvent;
import com.ziggfreed.common.playerstats.asset.StatValidator;
import com.ziggfreed.common.playerstats.board.StatBoardRankFeed;
import com.ziggfreed.common.playerstats.board.StatsBoardSource;
import com.ziggfreed.common.playerstats.command.ZigStatsCommand;
import com.ziggfreed.common.playerstats.encounter.EncounterBoardImport;
import com.ziggfreed.common.playerstats.encounter.EncounterStats;
import com.ziggfreed.common.playerstats.reader.ObjectiveCompletionsReader;
import com.ziggfreed.common.playerstats.round.EndlessBestWaves;
import com.ziggfreed.common.playerstats.round.RoundBestTimes;
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
 * source; the stat board's feed and source; the encounter stats' defeat and wipe listeners and the boot import of
 * the frozen encounter board; the round best times (dungeon runs' fastest clears); and the {@code /zigstats} family. The stat file store itself is registered
 * with the other framework stores. Registration only ({@code RootRegistrationOnlyTest}).
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
            // A boss defeat and a wipe count into the encounter stats; the frozen encounter board's rows rank
            // once at boot, when every stat and section file has folded.
            plugin.getEventRegistry().registerGlobal(EncounterDefeatedEvent.class, EncounterStats::onDefeated);
            plugin.getEventRegistry().registerGlobal(EncounterWipedEvent.class, EncounterStats::onWiped);
            plugin.getEventRegistry().register(BootEvent.class, event -> EncounterBoardImport.intoBoard());
        } catch (Throwable t) {
            SafeLog.warn("[stats] the encounter stats could not be registered", t);
        }
        try {
            // A won round's length is a best time in each stat that times its mod: the library times dungeon runs,
            // all but an endless one, whose reach is its best wave instead.
            RoundBestTimes.track(ZcStats.DUNGEON_BEST_TIME, DungeonRuns.MOD_ID, EndlessBestWaves::timed);
            plugin.getEventRegistry().registerGlobal(InstanceRoundCompletedEvent.class, RoundBestTimes::onCompleted);
            plugin.getEventRegistry().registerGlobal(DungeonWavesEvent.class, EndlessBestWaves::onWaves);
        } catch (Throwable t) {
            SafeLog.warn("[stats] the round best times could not be registered", t);
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
     * sections' lines; and the library's own native reader (objectives finished). A consumer that keeps
     * tallies of its own installs its source at its own setup, after this one.
     */
    public static void registerVocabulary() {
        ProgressionRuntime.defaults(OWNER).momentListener(new StatsMomentListener());
        CounterFactors.source(new StatsCounterSource());
        PlayerStats.rankInto(new StatBoardRankFeed());
        StatBoard.setSource(new StatsBoardSource());
        StatValidator.sections(StatsBoardSource::sectionLines);
        NativeReaders.register(OWNER, ObjectiveCompletionsReader.STAT_ID, new ObjectiveCompletionsReader());
    }
}
