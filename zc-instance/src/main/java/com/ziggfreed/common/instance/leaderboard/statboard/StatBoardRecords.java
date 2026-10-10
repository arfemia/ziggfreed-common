package com.ziggfreed.common.instance.leaderboard.statboard;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.i18n.ContentKeys;
import com.ziggfreed.common.i18n.Msg;
import com.ziggfreed.common.instance.leaderboard.Leaderboard;
import com.ziggfreed.common.instance.leaderboard.LeaderboardBucketTab;
import com.ziggfreed.common.instance.leaderboard.LeaderboardPageDeps;
import com.ziggfreed.common.instance.leaderboard.RecordsBoard;
import com.ziggfreed.common.instance.leaderboard.StatColumnDef;
import com.ziggfreed.common.occurrence.OccurrenceSource;
import com.ziggfreed.common.occurrence.Occurrences;
import com.ziggfreed.common.ui.menu.MenuSlot;
import com.ziggfreed.common.util.SafeLog;

/**
 * The stat board on Records: its sections as the first row of tabs, each with its own columns, and its
 * periods under them. A section shows only the lines the {@link StatBoardSource} gives a column, a
 * {@code Stat/*} line standing for every line the source names for that stat, at most
 * {@value StatColumnDef#MAX_STAT_COLUMNS}; a section left with none shows no tab; a range shows its tab from
 * the moment it starts; a season shows one tab per run, while it is live and after it ends for as long as
 * the board keeps it. The page shows the Stats view alone, with no "All" tab on either row, since
 * neither sections nor periods add up.
 *
 * <p>A tab reads the period's window at the board's own time ({@link StatClock}) each time the page
 * builds, so a page left open across a turnover reads the new window at its next click. As Records opens,
 * the opener's native readings are set first ({@link #opening}), so their own numbers stand as of now.
 */
public final class StatBoardRecords implements RecordsBoard {

    /** The one stat board on Records. */
    public static final StatBoardRecords INSTANCE = new StatBoardRecords();

    /** The board's own season tab name, under {@link StatBoardMessages#PREFIX}, for a season naming none. */
    private static final String SEASON_NAME = "period.season";

    private StatBoardRecords() {
    }

    @Override
    public boolean shows() {
        return deps() != null;
    }

    /** The opener's native readings go on the board first ({@link StatBoard#refresh}). */
    @Override
    public void opening(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref) {
        StatBoard.refresh(store, ref);
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
        List<LeaderboardBucketTab> periodTabs = periodTabs(board);
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

    /**
     * The columns {@code section} shows: each line the source gives one, in the file's order, a
     * {@code Stat/*} line standing for every line the source names for that stat, at most four in all.
     */
    @Nonnull
    private static List<StatColumnDef> columnsOf(@Nonnull StatSection section, @Nonnull StatBoardSource source) {
        List<StatColumnDef> out = new ArrayList<>();
        for (String written : section.lines()) {
            for (String line : linesOf(written, source)) {
                if (out.size() >= StatColumnDef.MAX_STAT_COLUMNS) {
                    return out;
                }
                StatColumnDef column = columnOf(line, source);
                if (column != null) {
                    out.add(column);
                }
            }
        }
        return out;
    }

    /** The lines {@code written} stands for: itself, or for {@code Stat/*} each line the source names now. */
    @Nonnull
    private static List<String> linesOf(@Nonnull String written, @Nonnull StatBoardSource source) {
        String stat = StatSection.everyLineOf(written);
        if (stat == null) {
            return List.of(written);
        }
        try {
            return source.everyLine(stat);
        } catch (RuntimeException e) {
            SafeLog.warn("[statboard] the stat source could not list the lines of '" + written + "', so it shows no"
                    + " column", e);
            return List.of();
        }
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

    /**
     * The period row: one tab per period that reads a window now, keyed by the period's id so a page left
     * open across a turnover reads the new window, and one per run a season shows, keyed by that run's own
     * bucket, which never moves.
     */
    @Nonnull
    private static List<LeaderboardBucketTab> periodTabs(@Nonnull Leaderboard board) {
        Instant now = StatClock.now();
        OccurrenceSource runs = Occurrences.source();
        Set<String> held = null;
        List<LeaderboardBucketTab> tabs = new ArrayList<>();
        for (StatPeriod period : StatPeriodConfig.getInstance().periods()) {
            if (period.kind() != PeriodKind.SEASON_RUN) {
                if (period.readKey(now) != null) {
                    tabs.add(new LeaderboardBucketTab(period.id(), name(period.nameKey(), period.id())));
                }
                continue;
            }
            if (held == null) {
                held = board.bucketKeys();
            }
            try {
                for (String bucket : period.readKeys(now, runs, held)) {
                    PeriodWindows.Run run = period.runOf(bucket);
                    if (run != null) {
                        tabs.add(LeaderboardBucketTab.withSpans(bucket, seasonName(period, run, runs)));
                    }
                }
            } catch (RuntimeException e) {
                SafeLog.warn("[statboard] the calendar could not say which runs the season '" + period.id()
                        + "' shows, so it shows none", e);
            }
        }
        return tabs;
    }

    /**
     * The bucket the period tab {@code tab} reads now: a period's running window, or a season's run as it is;
     * empty, which holds nothing, for a period gone since.
     */
    @Nonnull
    private static String bucketOf(@Nonnull String tab) {
        for (StatPeriod period : StatPeriodConfig.getInstance().periods()) {
            if (period.id().equals(tab)) {
                String key = period.readKey(StatClock.now());
                return key == null ? "" : key;
            }
            if (period.runOf(tab) != null) {
                return tab;
            }
        }
        return "";
    }

    /**
     * A season tab's name: the period's {@code NameKey} (the library's own when it names none) with the
     * event's name, a translation each player's client resolves, and the year its run belongs to. A run's
     * number never reaches a player, so every run of a year reads as that year. The tab paints it on a rich
     * button's {@code .TextSpans} ({@link LeaderboardBucketTab#withSpans}), the sink that substitutes both.
     */
    @Nonnull
    private static Message seasonName(@Nonnull StatPeriod period, @Nonnull PeriodWindows.Run run,
                                      @Nonnull OccurrenceSource runs) {
        String titleKey = runs.titleKey(run.eventId());
        Message event = titleKey != null ? ContentKeys.tr(titleKey) : Msg.raw(run.eventId());
        String nameKey = period.nameKey() != null ? period.nameKey() : StatBoardMessages.PREFIX + SEASON_NAME;
        // A year is a label, never a quantity: passed as text so no locale groups its digits (as the Almanac does).
        return Msg.key(nameKey, event, Integer.toString(run.year()));
    }

    @Nonnull
    private static Message name(@Nullable String nameKey, @Nonnull String id) {
        return nameKey != null ? Message.translation(nameKey) : Message.raw(id);
    }
}
