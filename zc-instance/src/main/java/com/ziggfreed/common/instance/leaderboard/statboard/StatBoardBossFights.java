package com.ziggfreed.common.instance.leaderboard.statboard;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.Message;
import com.ziggfreed.common.counter.CounterMap;
import com.ziggfreed.common.encounter.asset.EncounterBindingAsset;
import com.ziggfreed.common.encounter.asset.EncounterBindingConfig;
import com.ziggfreed.common.instance.leaderboard.EncounterBoards;
import com.ziggfreed.common.instance.leaderboard.Leaderboard;
import com.ziggfreed.common.instance.leaderboard.LeaderboardBucketTab;
import com.ziggfreed.common.instance.leaderboard.LeaderboardEntry;
import com.ziggfreed.common.instance.leaderboard.LeaderboardPageDeps;
import com.ziggfreed.common.instance.leaderboard.StatColumnDef;
import com.ziggfreed.common.ui.menu.MenuSlot;

/**
 * Records' Boss Fights: the stat board's boss fights, a fight per tab on the first row and the periods
 * under them. The columns are the {@value #SECTION} section's {@code Stat/*} lines, each read on one fight's
 * line ({@code Encounters_Won/<fight>}) and described by the {@link StatBoardSource} like any section line,
 * so a stat nothing on this server counts shows no column and a fight left with none shows no tab.
 *
 * <p>A fight is a key some player holds a line under in {@code all_time}, named and ordered by its binding
 * row ({@link EncounterBoards#fightTabs}); a key no row claims any more shows no tab. Like the sections,
 * the page shows the Stats view alone, with no "All" tab, and reads each period's window at the board's own
 * time when it builds.
 */
public final class StatBoardBossFights {

    /** The section whose {@code Stat/*} lines are the Boss Fights columns. */
    public static final String SECTION = "boss_fights";

    private StatBoardBossFights() {
    }

    /** The Boss Fights page, or null while the board is off or no fight on it has a record. */
    @Nullable
    public static LeaderboardPageDeps deps() {
        Leaderboard board = StatBoard.board();
        StatBoardSource source = StatBoard.source();
        if (board == null || !source.enabled()) {
            return null;
        }
        StatSectionConfig config = StatSectionConfig.getInstance();
        List<String> stats = statsOf(config.sections());
        if (stats.isEmpty()) {
            return null;
        }
        List<LeaderboardBucketTab> fights = new ArrayList<>();
        Map<String, List<StatColumnDef>> columns = new LinkedHashMap<>();
        // Every fight's tab takes the section's picture and its line on how to get on the board.
        String picture = picture(config);
        Message hint = StatBoardRecords.hint(config.hintKeyOf(SECTION));
        Map<String, String> pictures = new LinkedHashMap<>();
        Map<String, Message> hints = new LinkedHashMap<>();
        for (LeaderboardBucketTab fight : EncounterBoards.fightTabs(recorded(board, stats),
                EncounterBindingConfig.getInstance().all().values())) {
            List<StatColumnDef> shown = columnsOf(fight.bucketKey(), stats, source);
            if (!shown.isEmpty()) {
                fights.add(fight);
                columns.put(fight.bucketKey(), shown);
                pictures.put(fight.bucketKey(), picture);
                hints.put(fight.bucketKey(), hint);
            }
        }
        List<LeaderboardBucketTab> periods = StatBoardRecords.periodTabs(board);
        if (fights.isEmpty() || periods.isEmpty()) {
            return null;
        }
        return new LeaderboardPageDeps(board, fights, periods, List.of(), StatBoardMessages.INSTANCE,
                (fight, period) -> StatBoardRecords.bucketOf(period))
                .withColumnsByPrimary(columns)
                .withPrimaryPictures(pictures)
                .withPrimaryHints(hints)
                .withoutAllTabs()
                .statsOnly()
                .withMenuTab(MenuSlot.RECORDS.id());
    }

    /**
     * The picture of a fight's tab, and of Records' Boss Fights tab: the {@value #SECTION} section's {@code Icon},
     * else the stat board's own section picture.
     */
    @Nonnull
    public static String picture(@Nonnull StatSectionConfig config) {
        String icon = config.iconOf(SECTION);
        return icon != null ? icon : StatBoardRecords.PICTURE;
    }

    /** The first tab on {@code deps} that holds {@code row}'s records, or null when it has none. */
    @Nullable
    public static String tabOf(@Nonnull LeaderboardPageDeps deps, @Nonnull EncounterBindingAsset row) {
        EncounterBindingAsset.Leaderboard group = row.getLeaderboard();
        if (group == null) {
            return null;
        }
        for (LeaderboardBucketTab tab : deps.primaryTabs()) {
            if (EncounterBoards.holds(group, tab.bucketKey())) {
                return tab.bucketKey();
            }
        }
        return null;
    }

    /**
     * The stats the {@value #SECTION} section names as {@code Stat/*}, one column each per fight, in its
     * order, at most four; its other lines read nothing here.
     */
    @Nonnull
    private static List<String> statsOf(@Nonnull List<StatSection> sections) {
        List<String> stats = new ArrayList<>();
        for (StatSection section : sections) {
            if (!SECTION.equals(section.id())) {
                continue;
            }
            for (String line : section.lines()) {
                String stat = StatSection.everyLineOf(line);
                if (stat != null && stats.size() < StatColumnDef.MAX_STAT_COLUMNS) {
                    stats.add(stat);
                }
            }
        }
        return stats;
    }

    /** Every fight key some player holds a line of {@code stats} under in {@code all_time}. */
    @Nonnull
    private static Set<String> recorded(@Nonnull Leaderboard board, @Nonnull Collection<String> stats) {
        Set<String> wanted = new LinkedHashSet<>();
        for (String stat : stats) {
            wanted.add(stat.toLowerCase(Locale.ROOT));
        }
        Set<String> out = new LinkedHashSet<>();
        for (LeaderboardEntry entry : board.forBucket(StatPeriod.ALL_TIME).values()) {
            CounterMap counters = entry.counters;
            if (counters == null) {
                continue;
            }
            for (String key : counters.keys()) {
                int slash = key.indexOf('/');
                if (slash > 0 && slash < key.length() - 1
                        && wanted.contains(key.substring(0, slash).toLowerCase(Locale.ROOT))) {
                    out.add(key.substring(slash + 1));
                }
            }
        }
        return out;
    }

    /** One fight's columns: each stat's line for {@code fight} the source gives a column, in order. */
    @Nonnull
    private static List<StatColumnDef> columnsOf(@Nonnull String fight, @Nonnull List<String> stats,
            @Nonnull StatBoardSource source) {
        List<StatColumnDef> out = new ArrayList<>();
        for (String stat : stats) {
            StatColumnDef column = StatBoardRecords.columnOf(stat + "/" + fight, source);
            if (column != null) {
                out.add(column);
            }
        }
        return out;
    }
}
