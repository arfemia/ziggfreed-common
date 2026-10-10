package com.ziggfreed.common.instance.leaderboard.statboard;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.Message;
import com.ziggfreed.common.instance.leaderboard.Leaderboard;
import com.ziggfreed.common.instance.leaderboard.LeaderboardBucketTab;
import com.ziggfreed.common.instance.leaderboard.LeaderboardPageDeps;
import com.ziggfreed.common.instance.leaderboard.RecordsBoard;
import com.ziggfreed.common.instance.leaderboard.StatColumnDef;
import com.ziggfreed.common.ui.menu.MenuSlot;
import com.ziggfreed.common.util.SafeLog;

/**
 * The stat board on Records: its sections as the first row of tabs, each with its own columns, and its
 * periods under them. A section shows only the lines the {@link StatBoardSource} gives a column, at most
 * {@value StatColumnDef#MAX_STAT_COLUMNS}; a section left with none shows no tab; a range shows its tab from
 * the moment it starts. The page shows the Stats view alone, with no "All" tab on either row, since
 * neither sections nor periods add up.
 *
 * <p>A tab reads the period's window at the board's own time ({@link StatClock}) each time the page
 * builds, so a page left open across a turnover reads the new window at its next click.
 */
public final class StatBoardRecords implements RecordsBoard {

    /** The one stat board on Records. */
    public static final StatBoardRecords INSTANCE = new StatBoardRecords();

    private StatBoardRecords() {
    }

    @Override
    public boolean shows() {
        return deps() != null;
    }

    @Override
    @Nullable
    public LeaderboardPageDeps deps() {
        Leaderboard board = StatBoard.board();
        StatBoardSource source = StatBoard.source();
        if (board == null || !source.enabled()) {
            return null;
        }
        List<LeaderboardBucketTab> sectionTabs = new ArrayList<>();
        Map<String, List<StatColumnDef>> columns = new LinkedHashMap<>();
        for (StatSection section : StatSectionConfig.getInstance().sections()) {
            List<StatColumnDef> shown = columnsOf(section, source);
            if (!shown.isEmpty()) {
                sectionTabs.add(new LeaderboardBucketTab(section.id(), name(section.nameKey(), section.id())));
                columns.put(section.id(), shown);
            }
        }
        List<LeaderboardBucketTab> periodTabs = new ArrayList<>();
        for (StatPeriod period : StatPeriodConfig.getInstance().periods()) {
            if (period.readKey(StatClock.now()) != null) {
                periodTabs.add(new LeaderboardBucketTab(period.id(), name(period.nameKey(), period.id())));
            }
        }
        if (sectionTabs.isEmpty() || periodTabs.isEmpty()) {
            return null;
        }
        return new LeaderboardPageDeps(board, sectionTabs, periodTabs, List.of(), StatBoardMessages.INSTANCE,
                (section, period) -> bucketOf(period))
                .withColumnsByPrimary(columns)
                .withoutAllTabs()
                .statsOnly()
                .withMenuTab(MenuSlot.RECORDS.id());
    }

    /** The columns {@code section} shows: each line the source gives one, in the file's order, at most four. */
    @Nonnull
    private static List<StatColumnDef> columnsOf(@Nonnull StatSection section, @Nonnull StatBoardSource source) {
        List<StatColumnDef> out = new ArrayList<>();
        for (String line : section.lines()) {
            if (out.size() >= StatColumnDef.MAX_STAT_COLUMNS) {
                break;
            }
            StatColumnDef column = columnOf(line, source);
            if (column != null) {
                out.add(column);
            }
        }
        return out;
    }

    /** The source's column for {@code line}; a source that throws gives it none, and says so. */
    @Nullable
    private static StatColumnDef columnOf(@Nonnull String line, @Nonnull StatBoardSource source) {
        try {
            return source.column(line);
        } catch (RuntimeException e) {
            SafeLog.warn("[statboard] the stat source could not describe '" + line + "', so it shows no column", e);
            return null;
        }
    }

    /** The bucket the period tab {@code periodId} reads now; empty, which holds nothing, for a period gone since. */
    @Nonnull
    private static String bucketOf(@Nonnull String periodId) {
        for (StatPeriod period : StatPeriodConfig.getInstance().periods()) {
            if (period.id().equals(periodId)) {
                String key = period.readKey(StatClock.now());
                return key == null ? "" : key;
            }
        }
        return "";
    }

    @Nonnull
    private static Message name(@Nullable String nameKey, @Nonnull String id) {
        return nameKey != null ? Message.translation(nameKey) : Message.raw(id);
    }
}
