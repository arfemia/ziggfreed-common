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
import com.ziggfreed.common.instance.leaderboard.LeaderboardSplit;
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
 * neither sections nor periods add up. A section split in code ({@link StatSplit}) adds its row of groups and
 * its picker of single things under its tab.
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

    /**
     * The picture of a section tab whose file names no {@code Icon} and belongs to no event that has one: copper
     * bars, a nod to a bar chart, as the Achievements tab's Statistics view is pictured.
     */
    static final String PICTURE = "Ingredient_Bar_Copper";

    /** What separates a split stat from its group in a group's sum key ({@code Kills+BEASTS}). */
    private static final String SUM = "+";

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
        Map<String, String> pictures = new LinkedHashMap<>();
        Map<String, Message> hints = new LinkedHashMap<>();
        OccurrenceSource events = Occurrences.source();
        StatSectionConfig config = StatSectionConfig.getInstance();
        for (StatSection section : config.sections()) {
            List<StatColumnDef> shown = columnsOf(section, source);
            if (!shown.isEmpty()) {
                sectionTabs.add(new LeaderboardBucketTab(section.id(),
                        name(sectionNameKey(section, events), section.id())));
                columns.put(section.id(), shown);
                pictures.put(section.id(), sectionPicture(section, config, events));
                hints.put(section.id(), hint(config.hintKeyOf(section.id())));
            }
        }
        List<LeaderboardBucketTab> periodTabs = periodTabs(board);
        if (sectionTabs.isEmpty() || periodTabs.isEmpty()) {
            return null;
        }
        Map<String, LeaderboardSplit> splits = new LinkedHashMap<>();
        String allTime = allTimeTab(periodTabs);
        for (LeaderboardBucketTab tab : sectionTabs) {
            StatSplit split = config.splitOf(tab.bucketKey());
            LeaderboardSplit shown = split == null ? null : split(split, source, allTime);
            if (shown != null) {
                splits.put(tab.bucketKey(), shown);
            }
        }
        return new LeaderboardPageDeps(board, sectionTabs, periodTabs, List.of(), StatBoardMessages.INSTANCE,
                (section, period) -> bucketOf(period))
                .withColumnsByPrimary(columns)
                .withPrimaryPictures(pictures)
                .withPrimaryHints(hints)
                .withSplits(splits)
                .withoutAllTabs()
                .statsOnly()
                .withMenuTab(MenuSlot.RECORDS.id());
    }

    /**
     * A section's split as Records shows it ({@link StatSplit}): each group a choice whose columns sum its things'
     * lines, each thing a pick with its own lines, a line the source gives no column left out, and a group or a
     * thing left with none hidden. Pinned to {@code allTime} when every split stat holds all-time numbers alone (a
     * gauge), so a week never reads empty. Null when nothing is left, or the groups could not be read.
     */
    @Nullable
    static LeaderboardSplit split(@Nonnull StatSplit split, @Nonnull StatBoardSource source,
                                  @Nullable String allTime) {
        List<StatSplit.Group> groups;
        try {
            groups = split.groups().get();
        } catch (RuntimeException e) {
            SafeLog.warn("[statboard] a section's split could not list its groups, so its tab shows none", e);
            return null;
        }
        if (groups == null || split.columns().isEmpty()) {
            return null;
        }
        List<LeaderboardSplit.Choice> choices = new ArrayList<>();
        for (StatSplit.Group group : groups) {
            if (group == null || group.id().isBlank()) {
                continue;
            }
            List<LeaderboardSplit.Pick> picks = new ArrayList<>();
            List<List<String>> parts = new ArrayList<>();
            List<StatColumnDef> formats = new ArrayList<>();
            for (int c = 0; c < split.columns().size(); c++) {
                parts.add(new ArrayList<>());
                formats.add(null);
            }
            for (StatSplit.Thing thing : group.things()) {
                List<StatColumnDef> own = new ArrayList<>();
                for (int c = 0; c < split.columns().size(); c++) {
                    StatSplit.Column column = split.columns().get(c);
                    StatColumnDef line = columnOf(column.stat() + "/" + thing.id(), source);
                    if (line == null) {
                        continue;
                    }
                    own.add(new StatColumnDef(line.statKey(), column.label(), line.format(), line.ascending()));
                    parts.get(c).add(line.statKey());
                    formats.set(c, line);
                }
                if (!own.isEmpty()) {
                    picks.add(new LeaderboardSplit.Pick(thing.id(), thing.label(), own));
                }
            }
            List<StatColumnDef> sums = new ArrayList<>();
            for (int c = 0; c < split.columns().size(); c++) {
                StatSplit.Column column = split.columns().get(c);
                if (formats.get(c) != null) {
                    // Named by the stat and the group, a key no stored line can be (no '/' before the group).
                    sums.add(StatColumnDef.summing(column.stat() + SUM + group.id(), column.label(),
                            formats.get(c).format(), parts.get(c), column.partsOver()));
                }
            }
            if (!picks.isEmpty() && !sums.isEmpty()) {
                choices.add(new LeaderboardSplit.Choice(group.id(), group.label(), sums, picks));
            }
        }
        if (choices.isEmpty()) {
            return null;
        }
        boolean allTimeOnly = true;
        for (StatSplit.Column column : split.columns()) {
            allTimeOnly &= allTimeOnly(column.stat(), source);
        }
        return new LeaderboardSplit(split.allLabel(), split.pickAllLabel(), choices, split.shows(),
                allTimeOnly ? allTime : null);
    }

    /** Whether the source says {@code stat} holds all-time numbers alone; a source that throws says no. */
    private static boolean allTimeOnly(@Nonnull String stat, @Nonnull StatBoardSource source) {
        try {
            return source.allTimeOnly(stat);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** The period row's All Time tab (its period's id), or null when the row holds none. */
    @Nullable
    private static String allTimeTab(@Nonnull List<LeaderboardBucketTab> periodTabs) {
        for (StatPeriod period : StatPeriodConfig.getInstance().periods()) {
            if (period.kind() != PeriodKind.ALL_TIME) {
                continue;
            }
            for (LeaderboardBucketTab tab : periodTabs) {
                if (tab.bucketKey().equals(period.id())) {
                    return tab.bucketKey();
                }
            }
        }
        return null;
    }

    /**
     * The item a section's tab is pictured by: its file's {@code Icon}, else, for a section generated for a
     * calendar event, the event's own picture, else {@link #PICTURE}. Read as each page builds, as the name is.
     */
    @Nonnull
    static String sectionPicture(@Nonnull StatSection section, @Nonnull StatSectionConfig config,
                                 @Nonnull OccurrenceSource events) {
        String icon = config.iconOf(section.id());
        if (icon == null && section.event() != null) {
            try {
                String eventIcon = events.icon(section.event());
                icon = eventIcon == null || eventIcon.isBlank() ? null : eventIcon.trim();
            } catch (RuntimeException e) {
                icon = null;
            }
        }
        return icon != null ? icon : PICTURE;
    }

    /**
     * A tab's line on how to get on its board: its section's {@code HintKey} as the client resolves it, else the
     * board's own line, which says anything in the columns counts.
     */
    @Nonnull
    static Message hint(@Nullable String hintKey) {
        return hintKey != null ? Message.translation(ContentKeys.resolved(hintKey)) : StatBoardMessages.hint();
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
    static StatColumnDef columnOf(@Nonnull String line, @Nonnull StatBoardSource source) {
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
     * bucket, which never moves. The sections and Records' Boss Fights share it.
     */
    @Nonnull
    static List<LeaderboardBucketTab> periodTabs(@Nonnull Leaderboard board) {
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
    static String bucketOf(@Nonnull String tab) {
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

    /**
     * The key a section's tab is named by: its own {@code NameKey}, else, for a section generated for a calendar
     * event (a season page's), the event's title as the calendar has it. Read as each page builds, so a section
     * generated before the calendar loaded its events still takes the event's title. Null when neither is known,
     * and the tab shows the section's id; a calendar that throws counts as not knowing.
     */
    @Nullable
    static String sectionNameKey(@Nonnull StatSection section, @Nonnull OccurrenceSource events) {
        if (section.nameKey() != null || section.event() == null) {
            return section.nameKey();
        }
        try {
            String titleKey = events.titleKey(section.event());
            return titleKey == null || titleKey.isBlank() ? null : titleKey.trim();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * A tab's name: its key as the client resolves it ({@link ContentKeys#resolved}, so a pack's short key, such
     * as a season page's title a generated section is named by, finds its namespace), or the id when it names none.
     */
    @Nonnull
    private static Message name(@Nullable String nameKey, @Nonnull String id) {
        return nameKey != null ? Message.translation(ContentKeys.resolved(nameKey)) : Message.raw(id);
    }
}
