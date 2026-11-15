package com.ziggfreed.common.playerstats.round;

import java.util.List;
import java.util.UUID;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.instance.dungeon.DungeonMilestoneEvent;
import com.ziggfreed.common.playerstats.PlayerStats;
import com.ziggfreed.common.playerstats.PlayerThreads;
import com.ziggfreed.common.playerstats.StatBreakdown;
import com.ziggfreed.common.playerstats.ZcStats;
import com.ziggfreed.common.playerstats.asset.StatAsset;
import com.ziggfreed.common.playerstats.asset.StatConfig;
import com.ziggfreed.common.util.SafeLog;

/**
 * A dungeon run's milestones, counted for every member in the run's area as each fires ({@link DungeonMilestoneEvent}):
 * one more in {@code Dungeon_Milestones} ({@link PlayerStats#add}), on the line the stat file's own {@code Breakdown}
 * reads off the milestone id (its target) and the dungeon id (its qualifier), so a stat filing by {@code Target}
 * keeps one line per milestone id across every dungeon. A stat no file defines writes nothing; a member who is
 * offline is skipped.
 */
public final class DungeonMilestones {

    private static final String LOG = "[stats]";

    private DungeonMilestones() {
    }

    /** The bus entry point, guarded whole. */
    public static void onMilestone(@Nonnull DungeonMilestoneEvent event) {
        try {
            StatAsset stat = StatConfig.getInstance().index().stat(ZcStats.DUNGEON_MILESTONES);
            if (stat == null || event.members().isEmpty()) {
                return;
            }
            String line = lineOf(stat, event);
            List<String> stats = List.of(ZcStats.DUNGEON_MILESTONES);
            for (UUID member : event.members()) {
                PlayerThreads.run(member, LOG, "milestone", line == null ? ZcStats.DUNGEON_MILESTONES : line, stats,
                        (store, ref) -> PlayerStats.add(store, ref, ZcStats.DUNGEON_MILESTONES, line, 1L));
            }
        } catch (Throwable t) {
            SafeLog.warn(LOG + " a dungeon milestone could not be counted", t);
        }
    }

    /** The line {@code stat} files {@code event} under: its Breakdown over the milestone and dungeon ids; null for the total. */
    @Nullable
    private static String lineOf(@Nonnull StatAsset stat, @Nonnull DungeonMilestoneEvent event) {
        StatBreakdown breakdown = stat.breakdown();
        return breakdown == null ? null : breakdown.thing(event.milestoneId(), event.dungeonId());
    }
}
