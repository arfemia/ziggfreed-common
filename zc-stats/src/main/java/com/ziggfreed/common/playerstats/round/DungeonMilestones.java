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
 * keeps one line per milestone id across every dungeon. The library's three ids file under their own spelling
 * ({@link #lineId}) whatever case a volume wrote, so their lines never split by case; any other id files trimmed and
 * as written. A stat no file defines writes nothing; a member who is offline is skipped.
 */
public final class DungeonMilestones {

    private static final String LOG = "[stats]";

    /** The milestone ids the library names, in the spelling their lines file under. */
    private static final List<String> LIBRARY_IDS = List.of(DungeonMilestoneEvent.ROOM_CLEARED,
            DungeonMilestoneEvent.PUZZLE_SOLVED, DungeonMilestoneEvent.SECRET_FOUND);

    private DungeonMilestones() {
    }

    /**
     * The milestone id as its line files it: one of the library's three in {@link DungeonMilestoneEvent}'s own
     * spelling when it matches one without regard to case, else {@code id} trimmed, as written.
     */
    @Nonnull
    static String lineId(@Nonnull String id) {
        String trimmed = id.trim();
        for (String known : LIBRARY_IDS) {
            if (known.equalsIgnoreCase(trimmed)) {
                return known;
            }
        }
        return trimmed;
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

    /**
     * The line {@code stat} files {@code event} under: its Breakdown over the milestone id ({@link #lineId}) and the
     * dungeon id; null for the total.
     */
    @Nullable
    private static String lineOf(@Nonnull StatAsset stat, @Nonnull DungeonMilestoneEvent event) {
        StatBreakdown breakdown = stat.breakdown();
        return breakdown == null ? null : breakdown.thing(lineId(event.milestoneId()), event.dungeonId());
    }
}
