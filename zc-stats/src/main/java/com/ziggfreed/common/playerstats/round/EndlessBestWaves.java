package com.ziggfreed.common.playerstats.round;

import java.util.List;

import javax.annotation.Nonnull;

import com.ziggfreed.common.instance.dungeon.DungeonRuns;
import com.ziggfreed.common.instance.dungeon.DungeonWavesEvent;
import com.ziggfreed.common.instance.metadata.RoundMetadata;
import com.ziggfreed.common.playerstats.PlayerStats;
import com.ziggfreed.common.playerstats.PlayerThreads;
import com.ziggfreed.common.playerstats.ZcStats;
import com.ziggfreed.common.playerstats.asset.StatConfig;
import com.ziggfreed.common.util.SafeLog;

/**
 * An endless dungeon run's reach, counted as each player's best in {@code Dungeon_Best_Wave} as their part in a run
 * ends ({@link DungeonWavesEvent}): the most waves they cleared standing in one run, kept highest
 * ({@link PlayerStats#raise}) on the line the stat file's own {@code Breakdown} reads off the round contract's target
 * and qualifier ({@code dungeon:<difficulty>@<id>}), exactly as a dungeon round's other stats file it, so Records
 * and an achievement read the same line. A stat no file defines writes nothing; a player who is offline is skipped.
 */
public final class EndlessBestWaves {

    private static final String LOG = "[stats]";

    private EndlessBestWaves() {
    }

    /**
     * Whether a won dungeon round counts as a best time: any but an endless run's, which nobody wins fastest (its
     * reach is its best wave).
     */
    public static boolean timed(@Nonnull RoundMetadata round) {
        return !DungeonRuns.isEndless(round.presetId());
    }

    /** The bus entry point, guarded whole. */
    public static void onWaves(@Nonnull DungeonWavesEvent event) {
        try {
            if (event.waves() <= 0 || StatConfig.getInstance().index().stat(ZcStats.DUNGEON_BEST_WAVE) == null) {
                return;
            }
            RoundMetadata round = RoundMetadata.builder(DungeonRuns.MOD_ID).modeId(event.difficulty())
                    .presetId(event.dungeonId()).build();
            String line = RoundBestTimes.lineOf(ZcStats.DUNGEON_BEST_WAVE, round);
            long waves = event.waves();
            PlayerThreads.run(event.player(), LOG, "endless", line == null ? ZcStats.DUNGEON_BEST_WAVE : line,
                    List.of(ZcStats.DUNGEON_BEST_WAVE),
                    (store, ref) -> PlayerStats.raise(store, ref, ZcStats.DUNGEON_BEST_WAVE, line, waves));
        } catch (Throwable t) {
            SafeLog.warn(LOG + " an endless run's best wave could not be counted", t);
        }
    }
}
