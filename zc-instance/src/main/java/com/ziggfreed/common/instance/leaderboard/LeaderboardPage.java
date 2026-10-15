package com.ziggfreed.common.instance.leaderboard;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.protocol.packets.interface_.Page;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.ui.DropdownEntryInfo;
import com.hypixel.hytale.server.core.ui.LocalizableString;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import com.ziggfreed.common.i18n.LangCatalog;
import com.ziggfreed.common.i18n.Msg;
import com.ziggfreed.common.instance.result.ColumnFormat;
import com.ziggfreed.common.ui.UiRetint;
import com.ziggfreed.common.ui.UiText;
import com.ziggfreed.common.ui.ZigRichButton;
import com.ziggfreed.common.ui.kit.EmptyState;
import com.ziggfreed.common.ui.kit.EmptyStatePainter;
import com.ziggfreed.common.ui.kit.Picture;
import com.ziggfreed.common.ui.kit.SegmentPainter;
import com.ziggfreed.common.ui.kit.Stat;
import com.ziggfreed.common.ui.kit.StatPainter;
import com.ziggfreed.common.ui.kit.Tone;
import com.ziggfreed.common.ui.kit.ViewTabPainter;
import com.ziggfreed.common.ui.kit.ZigStyles;
import com.ziggfreed.common.ui.kit.ZigTokens;
import com.ziggfreed.common.ui.menu.MenuFrame;
import com.ziggfreed.common.ui.menu.MenuRail;
import com.ziggfreed.common.ui.menu.ZigMenu;
import com.ziggfreed.common.ui.name.PlayerDisplayNames;

/**
 * The generic leaderboard page (the reusable lift of {@code KweebecLeaderboardPage}). Two tab
 * axes (an optional PRIMARY axis over the SECONDARY axis), a three-metric sort (best score / total
 * points / best time) and a Rankings/Stats view on a board that keeps both, the viewer's own strip
 * (their place and their number in each column, or the line saying how to get on the board), and a
 * scrollable ranked list (the top three gold, silver and bronze, the viewer's row on the selected
 * row's fill). The {@code ResultsPage} CTA deep-links here to the just-played bucket.
 *
 * <p>Built from zc's kit: a first row whose tabs each bring their own columns (a stat board's
 * sections) is a row of view tabs ({@link ViewTabPainter}), every other row a
 * segmented control ({@link SegmentPainter}), the viewer's figures {@code @ZigStat}s
 * ({@link StatPainter}), the empty board the kit's empty state ({@link EmptyStatePainter}), and
 * every colour a {@link ZigTokens} token or a {@link ZigStyles} named style. Every line goes on a
 * {@code .TextSpans}; a column header's tooltip goes through {@link UiText#setTooltip}, the one
 * tooltip writer.
 *
 * <p>Consumer policy (the {@link Leaderboard} for the mode, the tab axes, the stat columns, the
 * chrome) is supplied through {@link LeaderboardPageDeps}; the page is mod-agnostic. When the
 * primary axis / stat columns are empty it degrades to the original single-axis, score-only board.
 * The page is stateless across events: each interaction reopens it with the next
 * (primary, secondary, sort, statSort, view) state carried on every event binding. Every
 * {@code handleDataEvent} exit path sends a response.
 *
 * <p>With {@link LeaderboardPageDeps#menuTab} set, the same body sits in the shared menu frame with that
 * tab selected on the rail, and a rail click is answered before the page reads its own action; every
 * reopen carries the deps, and so the rail. Without it the page keeps its own frame.
 *
 * <p>Each tab axis with two or more concrete tabs synthesizes a trailing "All" tab (reserved
 * bucket key {@code "*"}) that aggregates every concrete bucket on that axis, so All+All is the
 * full lifetime roll-up. The Stats view honors the tab selection and is sortable by each stat
 * column (and Plays) via clickable column headers, a tie broken by the other columns from the left; the
 * Rankings view keeps its three-metric sort.
 *
 * <p>A primary tab with a split ({@link LeaderboardSplit}) adds a row under the others: its
 * choices as segments and a picker of single things, each with its own columns over the same buckets; a choice
 * other than the first, or a pick, may pin the second row to one tab and hide it. A split with a periodic view
 * ({@link LeaderboardSplit#periods}) instead shows each selection's periodic columns on every second-row tab but
 * its all-time one, with the selection's all-time figure beside each name. A pick with variants
 * ({@link LeaderboardSplit.Pick#variants}, a boss's party sizes) adds one more row, {@code #VariantRow}: the pick's
 * own columns first, then each variant's. A new choice clears the pick and its variant, a new pick its variant.
 */
public class LeaderboardPage extends InteractiveCustomUIPage<LeaderboardEventData> {

    private static final String PAGE_TEMPLATE = "Pages/ZigLeaderboardPage.ui";
    /** The same body inside the shared menu frame, for deps that name a rail tab ({@link LeaderboardPageDeps#menuTab}). */
    private static final String MENU_PAGE_TEMPLATE = "Pages/ZigLeaderboardMenuPage.ui";
    private static final String ROW_TEMPLATE = "Pages/ZigLeaderboardRow.ui";
    private static final String STATS_ROW_TEMPLATE = "Pages/ZigLeaderboardStatsRow.ui";
    private static final int MAX_ROWS = 100;
    // Synthesized "All" tab: a reserved bucket key (no real preset id / party size contains it),
    // appended to the end of any axis with >= 2 concrete tabs; selecting it aggregates that axis.
    private static final String ALL = "*";
    /** The action a primary-row link carries: open the linked board's page ({@link LeaderboardPageDeps#primaryLinks}). */
    private static final String LINK = "link";
    /** The picker of a tab's split ({@link LeaderboardSplit}): its first entry, the chosen choice's own columns. */
    private static final String PICK = "#Pick";
    /**
     * The key the picker's binding carries its live value under, which the client echoes back as it is
     * ({@link LeaderboardEventData} reads it into {@code pick}).
     */
    static final String PICK_ECHO = "@Pick";
    /**
     * What a column's own label key takes for its tooltip: {@code <label key>.tip}, a bare translation saying what
     * the column counts, used when the server ships one ({@link #columnTip}).
     */
    static final String TIP_SUFFIX = ".tip";
    /** What separates a row's name from the figure beside it ({@link #withMeta}). */
    private static final String META_GAP = "   ";
    /** The figures the viewer's strip holds beside their place ({@code #YouStat0..#YouStat3}). */
    private static final int YOU_FIGURES = 4;
    /** The words of a Rankings row the viewer's own row turns bright, beside its rank. */
    private static final List<String> RANKING_CELLS = List.of("#Player", "#Total", "#Score", "#Time", "#Plays");
    /** The words of a Stats row the viewer's own row turns bright, beside its rank. */
    private static final List<String> STATS_CELLS = List.of("#Player", "#Col0", "#Col1", "#Col2", "#Col3", "#Plays");

    /** The top three places, each with the token its trophy and its number are tinted in. */
    private enum Medal {
        GOLD(ZigTokens.ACCENT),
        SILVER(ZigTokens.INK_STRONG),
        BRONZE(ZigTokens.TONE_AVAILABLE_FILL);

        private final String colour;

        Medal(@Nonnull String colour) {
            this.colour = colour;
        }

        /** The medal of place {@code rank}, or null past the third. */
        @Nullable
        static Medal of(int rank) {
            return rank >= 1 && rank <= 3 ? values()[rank - 1] : null;
        }
    }

    private final LeaderboardPageDeps deps;
    private final String activePrimary; // "" when there is no primary axis
    private final String activeSecondary;
    private final SortMode sort;
    private final String statSort; // the active Stats-view column metric ("" when no stat columns)
    private final boolean statsView;
    /** The selected primary tab's split, or null when it has none. */
    @Nullable private final LeaderboardSplit split;
    /** The split's chosen choice ("" for its first, the tab's own columns). */
    private final String activeSplit;
    /** The split's picked thing ("" for none). */
    private final String activePick;
    /** The picked thing's chosen variant ("" for none: the thing's own columns). */
    private final String activeVariant;
    /**
     * The columns the selection shows: the variant's, else the pick's, else the choice's, else the primary tab's own,
     * each its periodic ones off the split's all-time tab ({@link LeaderboardSplit#periods}) when it has some.
     */
    private final List<StatColumnDef> viewColumns;
    /**
     * The column each row's figure beside its name reads on the all-time bucket, off the split's all-time tab
     * ({@link LeaderboardSplit.Periodic#meta}); null shows the name alone.
     */
    @Nullable private final StatColumnDef metaColumn;

    /** The rail this build painted (none when the deps name no tab), which a rail click is routed through. */
    @Nonnull private MenuRail rail = MenuRail.EMPTY;

    /**
     * Open the leaderboard with no deep-link context: defaults BOTH tab axes to the synthesized
     * "All" selection (reserved key {@link #ALL}), so a fresh open shows the All-difficulty x
     * All-party-size lifetime roll-up. An axis with fewer than two concrete tabs has no "All" tab,
     * so {@link #resolveKey} falls back to its first concrete tab (or {@code ""} for an empty axis).
     */
    public LeaderboardPage(@Nonnull PlayerRef playerRef, @Nonnull LeaderboardPageDeps deps) {
        this(playerRef, deps, ALL, ALL, SortMode.BEST_SCORE, null, false);
    }

    /**
     * Deep-link convenience: {@code bucket} is a combined {@code "<primary>_<secondary>"} key (split
     * on the first underscore when BOTH axes exist) or, for a single-axis board, the whole bucket is
     * that axis's key (the legacy secondary-only board, or a primary-only board whose preset ids may
     * themselves contain underscores - e.g. PvP {@code "clash_1v1"}).
     */
    public LeaderboardPage(@Nonnull PlayerRef playerRef, @Nonnull LeaderboardPageDeps deps,
                           @Nullable String bucket) {
        this(playerRef, deps, splitPrimary(deps, bucket), splitSecondary(deps, bucket),
                SortMode.BEST_SCORE, null, false);
    }

    public LeaderboardPage(@Nonnull PlayerRef playerRef, @Nonnull LeaderboardPageDeps deps,
                           @Nullable String primary, @Nullable String secondary,
                           @Nullable SortMode sort, @Nullable String statSort, boolean statsView) {
        this(playerRef, deps, primary, secondary, sort, statSort, statsView, null, null);
    }

    /**
     * As the seven-argument form, with the selected primary tab's split state: {@code split} a choice's key and
     * {@code pick} a thing's ({@link LeaderboardSplit}); null, empty or unknown to the viewer is the split's first
     * choice, or no pick.
     */
    public LeaderboardPage(@Nonnull PlayerRef playerRef, @Nonnull LeaderboardPageDeps deps,
                           @Nullable String primary, @Nullable String secondary,
                           @Nullable SortMode sort, @Nullable String statSort, boolean statsView,
                           @Nullable String split, @Nullable String pick) {
        this(playerRef, deps, primary, secondary, sort, statSort, statsView, split, pick, null);
    }

    /**
     * As the nine-argument form, with the picked thing's variant: {@code variant} a variant's key
     * ({@link LeaderboardSplit.Variant}); null, empty or unknown to the pick is the thing's own columns.
     */
    public LeaderboardPage(@Nonnull PlayerRef playerRef, @Nonnull LeaderboardPageDeps deps,
                           @Nullable String primary, @Nullable String secondary,
                           @Nullable SortMode sort, @Nullable String statSort, boolean statsView,
                           @Nullable String split, @Nullable String pick, @Nullable String variant) {
        super(playerRef, CustomPageLifetime.CanDismissOrCloseThroughInteraction, LeaderboardEventData.CODEC);
        this.deps = deps;
        this.activePrimary = resolveKey(deps.primaryTabs(), primary, deps.allTabs());
        this.split = deps.split(activePrimary);
        LeaderboardSplit.Choice choice = this.split == null ? null : visibleChoice(this.split, split, playerRef);
        this.activeSplit = choice == null ? "" : choice.key();
        LeaderboardSplit.Pick picked = this.split == null ? null : visiblePick(this.split, choice, pick, playerRef);
        this.activePick = picked == null ? "" : picked.key();
        LeaderboardSplit.Variant chosen = picked == null ? null : variantOf(picked, variant);
        this.activeVariant = chosen == null ? "" : chosen.key();
        this.activeSecondary = pinned() ? this.split.pinnedTab()
                : resolveKey(deps.tabs(), secondary, deps.allTabs());
        // Off the split's all-time tab, a selection with columns of its own for a window shows them, and the figure
        // its rows read from all time beside each name.
        LeaderboardSplit.Periodic periodic = !offAllTime() ? null
                : chosen != null ? chosen.periodic()
                : picked != null ? picked.periodic()
                : choice != null ? choice.periodic() : this.split.periods().first();
        List<StatColumnDef> allTime = chosen != null ? chosen.columns()
                : picked != null ? picked.columns()
                : choice != null ? choice.columns() : deps.statColumns(activePrimary);
        this.viewColumns = periodic != null && !periodic.columns().isEmpty() ? periodic.columns() : allTime;
        this.metaColumn = periodic == null || this.split.periods().allTimeTab() == null
                || this.split.periods().words() == null ? null : periodic.meta();
        this.sort = sort != null ? sort : SortMode.BEST_SCORE;
        this.statSort = resolveStatKey(viewColumns, statSort, deps.isStatsOnly());
        // A stats-only board has no other view to fall back to, so it shows the Stats view even when the
        // selected tab has no columns.
        this.statsView = deps.isStatsOnly() || (statsView && !viewColumns.isEmpty());
    }

    /** The Stats columns the selection shows: a pick's, else a split choice's, else the primary tab's own. */
    @Nonnull
    private List<StatColumnDef> columns() {
        return viewColumns;
    }

    /**
     * Whether the second row is pinned to the split's {@link LeaderboardSplit#pinnedTab} and hidden: on a choice
     * other than the first, or a pick, of a split that pins one the row holds.
     */
    private boolean pinned() {
        if (split == null || split.pinnedTab() == null || (activeSplit.isEmpty() && activePick.isEmpty())) {
            return false;
        }
        for (LeaderboardBucketTab tab : deps.tabs()) {
            if (tab.bucketKey().equals(split.pinnedTab())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether the split reads its periodic view ({@link LeaderboardSplit#periods}): it has one and the second row's
     * selected tab is not its all-time one.
     */
    private boolean offAllTime() {
        return split != null && split.periods() != null && !activeSecondary.equals(split.periods().allTimeTab());
    }

    /** The split's choices {@code viewer} sees: each with a pick they see. */
    @Nonnull
    private static List<LeaderboardSplit.Choice> visibleChoices(@Nonnull LeaderboardSplit split,
                                                                @Nonnull PlayerRef viewer) {
        List<LeaderboardSplit.Choice> out = new ArrayList<>();
        for (LeaderboardSplit.Choice choice : split.choices()) {
            if (!visiblePicks(split, List.of(choice), viewer).isEmpty()) {
                out.add(choice);
            }
        }
        return out;
    }

    /** The picks of {@code choices} {@code viewer} sees, each once, in order. */
    @Nonnull
    private static List<LeaderboardSplit.Pick> visiblePicks(@Nonnull LeaderboardSplit split,
                                                            @Nonnull List<LeaderboardSplit.Choice> choices,
                                                            @Nonnull PlayerRef viewer) {
        Map<String, LeaderboardSplit.Pick> out = new LinkedHashMap<>();
        for (LeaderboardSplit.Choice choice : choices) {
            for (LeaderboardSplit.Pick pick : choice.picks()) {
                if (!out.containsKey(pick.key()) && shows(split, viewer, pick.key())) {
                    out.put(pick.key(), pick);
                }
            }
        }
        return new ArrayList<>(out.values());
    }

    /** Whether {@code viewer} sees {@code pick}; a filter that throws shows it. */
    private static boolean shows(@Nonnull LeaderboardSplit split, @Nonnull PlayerRef viewer, @Nonnull String pick) {
        try {
            return split.shows().test(viewer, pick);
        } catch (RuntimeException e) {
            return true;
        }
    }

    /** The visible choice keyed {@code key}, or null for the first choice (the tab's own columns). */
    @Nullable
    private static LeaderboardSplit.Choice visibleChoice(@Nonnull LeaderboardSplit split, @Nullable String key,
                                                         @Nonnull PlayerRef viewer) {
        if (key == null || key.isEmpty()) {
            return null;
        }
        for (LeaderboardSplit.Choice choice : visibleChoices(split, viewer)) {
            if (choice.key().equals(key)) {
                return choice;
            }
        }
        return null;
    }

    /**
     * The picks the picker offers: the chosen choice's, or under the first every visible choice's and then each pick
     * in no group ({@link LeaderboardSplit#ungrouped}) the viewer sees.
     */
    @Nonnull
    private static List<LeaderboardSplit.Pick> offered(@Nonnull LeaderboardSplit split,
                                                       @Nullable LeaderboardSplit.Choice choice,
                                                       @Nonnull PlayerRef viewer) {
        if (choice != null) {
            return visiblePicks(split, List.of(choice), viewer);
        }
        List<LeaderboardSplit.Choice> every = new ArrayList<>(visibleChoices(split, viewer));
        if (!split.ungrouped().isEmpty()) {
            // The picks in no group ride as one more choice of the picker's own, never a segment.
            every.add(new LeaderboardSplit.Choice(ALL, split.allLabel(), List.of(), split.ungrouped()));
        }
        return visiblePicks(split, every, viewer);
    }

    /** The variant of {@code pick} keyed {@code key}, or null for none (the pick's own columns). */
    @Nullable
    private static LeaderboardSplit.Variant variantOf(@Nonnull LeaderboardSplit.Pick pick, @Nullable String key) {
        if (key == null || key.isEmpty()) {
            return null;
        }
        for (LeaderboardSplit.Variant variant : pick.variants()) {
            if (variant.key().equals(key)) {
                return variant;
            }
        }
        return null;
    }

    /** The offered pick keyed {@code key}, or null for none. */
    @Nullable
    private static LeaderboardSplit.Pick visiblePick(@Nonnull LeaderboardSplit split,
                                                     @Nullable LeaderboardSplit.Choice choice, @Nullable String key,
                                                     @Nonnull PlayerRef viewer) {
        if (key == null || key.isEmpty() || ALL.equals(key)) {
            return null;
        }
        for (LeaderboardSplit.Pick pick : offered(split, choice, viewer)) {
            if (pick.key().equals(key)) {
                return pick;
            }
        }
        return null;
    }

    /** How many of {@link #columns()} the page shows: the stats row holds {@value StatColumnDef#MAX_STAT_COLUMNS}. */
    private int shownColumns() {
        return Math.min(columns().size(), StatColumnDef.MAX_STAT_COLUMNS);
    }

    @Nonnull
    private static String resolveKey(@Nonnull List<LeaderboardBucketTab> tabs, @Nullable String key,
                                     boolean allTabs) {
        if (ALL.equals(key) && hasAllTab(tabs, allTabs)) {
            return ALL;
        }
        if (key != null) {
            for (LeaderboardBucketTab tab : tabs) {
                if (tab.bucketKey().equals(key)) {
                    return key;
                }
            }
        }
        return tabs.isEmpty() ? "" : tabs.get(0).bucketKey();
    }

    /**
     * An axis synthesizes a trailing "All" tab only when it has two or more concrete tabs, and only on a
     * board that keeps them ({@link LeaderboardPageDeps#allTabs()}).
     */
    private static boolean hasAllTab(@Nonnull List<LeaderboardBucketTab> tabs, boolean allTabs) {
        return allTabs && tabs.size() >= 2;
    }

    /**
     * Resolve the active Stats-view sort key: a valid stat-column key, the reserved {@code "plays"}
     * or {@code "total"} (never on a stats-only board, which shows neither), else the first stat column
     * (the default). An empty stat-column list yields {@code ""}.
     */
    @Nonnull
    private static String resolveStatKey(@Nonnull List<StatColumnDef> columns, @Nullable String key,
                                         boolean statsOnly) {
        if (key != null && !key.isBlank()) {
            if (!statsOnly && ("plays".equals(key) || "total".equals(key))) {
                return key;
            }
            for (StatColumnDef c : columns) {
                if (c.statKey().equals(key)) {
                    return key;
                }
            }
        }
        return columns.isEmpty() ? "" : columns.get(0).statKey();
    }

    @Nullable
    private static String splitPrimary(@Nonnull LeaderboardPageDeps deps, @Nullable String bucket) {
        if (bucket == null || deps.primaryTabs().isEmpty()) {
            return null;
        }
        if (deps.tabs().isEmpty()) {
            return bucket; // single primary axis (e.g. PvP mode): the whole bucket IS the primary key
        }
        int i = bucket.indexOf('_');
        return i > 0 ? bucket.substring(0, i) : null;
    }

    @Nullable
    private static String splitSecondary(@Nonnull LeaderboardPageDeps deps, @Nullable String bucket) {
        if (bucket == null) {
            return null;
        }
        if (deps.primaryTabs().isEmpty()) {
            return bucket; // single secondary axis (legacy board): the whole bucket is the secondary key
        }
        if (deps.tabs().isEmpty()) {
            return null; // no secondary axis: nothing to split off the primary key
        }
        int i = bucket.indexOf('_');
        return i > 0 ? bucket.substring(i + 1) : bucket;
    }

    /**
     * The concrete {@link Leaderboard} bucket keys the current tab selection resolves to. A concrete
     * tab is a 1-element set; the synthesized "All" selection on an axis expands to every concrete tab
     * key on that axis. With both axes present the result is their cross product, each pair named by
     * the deps' {@link LeaderboardPageDeps#bucketKey} ({@code "<primary>_<secondary>"} unless the board
     * composes its own); with only one axis it is that axis's keys directly.
     * {@link Leaderboard#forBuckets} merges the set, so a single concrete bucket, an "All" axis
     * roll-up, and the All+All lifetime view all read the same way and stay consistent between the
     * Rankings and Stats views.
     */
    @Nonnull
    private List<String> effectiveBuckets() {
        return bucketsFor(activeSecondary);
    }

    /** The bucket keys the selection resolves to with {@code secondary} as the second row's tab ({@link #effectiveBuckets}). */
    @Nonnull
    private List<String> bucketsFor(@Nonnull String secondary) {
        List<String> primaries = ALL.equals(activePrimary) ? concreteKeys(deps.primaryTabs()) : List.of(activePrimary);
        List<String> secondaries = ALL.equals(secondary) ? concreteKeys(deps.tabs()) : List.of(secondary);
        if (deps.primaryTabs().isEmpty()) {
            return secondaries; // single secondary axis (legacy board)
        }
        if (deps.tabs().isEmpty()) {
            return primaries; // single primary axis (e.g. PvP mode)
        }
        List<String> out = new ArrayList<>(primaries.size() * secondaries.size());
        for (String p : primaries) {
            for (String s : secondaries) {
                out.add(deps.bucketKey(p, s));
            }
        }
        return out;
    }

    /** The concrete bucket keys of an axis (the "All" sentinel is never one of them). */
    @Nonnull
    private static List<String> concreteKeys(@Nonnull List<LeaderboardBucketTab> tabs) {
        List<String> out = new ArrayList<>(tabs.size());
        for (LeaderboardBucketTab tab : tabs) {
            out.add(tab.bucketKey());
        }
        return out;
    }

    @Override
    public void build(@Nonnull Ref<EntityStore> ref, @Nonnull UICommandBuilder cmd,
                      @Nonnull UIEventBuilder events, @Nonnull Store<EntityStore> store) {
        LeaderboardScreenMessages t = deps.text();
        String menuTab = deps.menuTab();
        if (menuTab == null) {
            cmd.append(PAGE_TEMPLATE);
        } else {
            // On the shared menu's rail: the same body in the menu frame, the rail painted with the
            // deps' tab selected before anything else, so every reopen (tab, sort, view) keeps it.
            ZigMenu.appendThemed(cmd, MENU_PAGE_TEMPLATE, MenuFrame.RAIL);
            rail = ZigMenu.paint(cmd, events, store, ref, store.getComponent(ref, Player.getComponentType()),
                    menuTab, false);
        }
        events.addEventBinding(CustomUIEventBindingType.Activating, "#CloseButton", EventData.of("Action", "close"));

        cmd.set("#Title.TextSpans", t.title());
        optional(cmd, "#Lead", t.lead());
        paintTabRows(cmd, events, t);
        paintControls(cmd, events, t);

        List<Map.Entry<UUID, LeaderboardEntry>> sorted = statsView ? statsSorted() : rankingsSorted();
        boolean empty = sorted.isEmpty();
        cmd.set("#YouStrip.Visible", !empty);
        cmd.set("#TableHeader.Visible", !statsView && !empty);
        cmd.set("#StatsHeader.Visible", statsView && !empty);
        cmd.set("#LeaderboardList.Visible", !empty);
        cmd.set("#EmptyState.Visible", empty);
        if (empty) {
            // Nobody on the board: what counts here and how to get on it, in the list's place.
            EmptyStatePainter.paint(cmd, events, "#EmptyState", new EmptyState(
                    Picture.item(deps.primaryPicture(activePrimary)), t.empty(), deps.primaryHint(activePrimary), null),
                    null);
            return;
        }
        int self = indexOf(sorted, playerRef.getUuid());
        if (statsView) {
            paintStatsHeader(cmd, events, t);
            paintStatsRows(cmd, t, sorted, self);
        } else {
            paintRankingsHeader(cmd, t);
            paintRankingRows(cmd, sorted, self);
        }
        paintYou(cmd, t, sorted, self);
    }

    // ==================== the rows of tabs and the controls ====================

    /**
     * The first row (sections, difficulty), its rule when it is a row of view tabs, and the second (periods,
     * party size), each with its optional label above it.
     */
    private void paintTabRows(@Nonnull UICommandBuilder cmd, @Nonnull UIEventBuilder events,
                              @Nonnull LeaderboardScreenMessages t) {
        boolean views = deps.primaryTabsCarryColumns();
        boolean primary = !deps.primaryTabs().isEmpty();
        // A split choice that pins the second row reads its pinned tab with the row hidden.
        boolean secondary = !deps.tabs().isEmpty() && !pinned();
        cmd.set("#GroupRow.Visible", primary);
        cmd.set("#GroupRule.Visible", primary && views);
        cmd.set("#TabRow.Visible", secondary);
        optional(cmd, "#GroupLabel", t.primaryAxisLabel());
        optional(cmd, "#TabLabel", t.secondaryAxisLabel());
        if (primary) {
            buildTabRow(cmd, events, t, "#GroupBar", deps.primaryTabs(), activePrimary, "group", views);
        }
        if (secondary) {
            buildTabRow(cmd, events, t, "#TabBar", deps.tabs(), activeSecondary, "tab", false);
        }
        paintSplitRow(cmd, events);
    }

    /**
     * The selected tab's split, when it has one: its choices as segments (the first, the tab's own columns, then
     * each group the viewer sees a pick of) and the picker of single things, whose first entry is the chosen
     * choice's own columns. A choice click clears the pick and its variant; a pick keeps the choice and clears the
     * variant. Then the picked thing's variants, when it has some ({@link #paintVariantRow}).
     */
    private void paintSplitRow(@Nonnull UICommandBuilder cmd, @Nonnull UIEventBuilder events) {
        cmd.set("#SplitRow.Visible", split != null);
        if (split == null) {
            cmd.set("#VariantRow.Visible", false);
            return;
        }
        String viewStr = statsView ? "stats" : "rankings";
        appendTab(cmd, events, "#SplitBar", split.allLabel(), "", activeSplit.isEmpty(), false,
                binding("split", activePrimary, activeSecondary, sort.name(), statSort, viewStr, "", "", ""));
        LeaderboardSplit.Choice chosen = null;
        for (LeaderboardSplit.Choice choice : visibleChoices(split, playerRef)) {
            boolean on = choice.key().equals(activeSplit);
            if (on) {
                chosen = choice;
            }
            appendTab(cmd, events, "#SplitBar", choice.label(), choice.key(), on, false,
                    binding("split", activePrimary, activeSecondary, sort.name(), statSort, viewStr, choice.key(), "",
                            ""));
        }
        // A dropdown entry is a String sink the client resolves only from a key: a bare translated name goes as its
        // key, one with params or a name nothing translates (one a server made up) as its plain words.
        List<DropdownEntryInfo> entries = new ArrayList<>();
        entries.add(new DropdownEntryInfo(entryLabel(split.pickAllLabel()), ALL));
        LeaderboardSplit.Pick picked = null;
        for (LeaderboardSplit.Pick pick : offered(split, chosen, playerRef)) {
            entries.add(new DropdownEntryInfo(entryLabel(pick.label()), pick.key()));
            if (pick.key().equals(activePick)) {
                picked = pick;
            }
        }
        cmd.set(PICK + ".Entries", entries);
        cmd.set(PICK + ".Value", activePick.isEmpty() ? ALL : activePick);
        // The binding carries no Pick of its own: the client's echo of the live value is the pick. A new pick starts
        // on its own columns.
        events.addEventBinding(CustomUIEventBindingType.ValueChanged, PICK,
                binding("pick", activePrimary, activeSecondary, sort.name(), statSort, viewStr, activeSplit, null, "")
                        .append(PICK_ECHO, PICK + ".Value"), false);
        paintVariantRow(cmd, events, picked, viewStr);
    }

    /**
     * The picked thing's variants, when it has some: its own columns first ({@link LeaderboardSplit#variantAllLabel},
     * else the board's All), then each variant, as two-line segments whose labels go on {@code .TextSpans}, so a
     * typed number in one fills. Hidden with no pick, or a pick with none.
     */
    private void paintVariantRow(@Nonnull UICommandBuilder cmd, @Nonnull UIEventBuilder events,
                                 @Nullable LeaderboardSplit.Pick picked, @Nonnull String viewStr) {
        boolean shown = picked != null && !picked.variants().isEmpty();
        cmd.set("#VariantRow.Visible", shown);
        if (!shown) {
            return;
        }
        Message all = split.variantAllLabel() != null ? split.variantAllLabel() : deps.text().filterAll();
        appendTab(cmd, events, "#VariantBar", all, "", activeVariant.isEmpty(), false,
                binding("variant", activePrimary, activeSecondary, sort.name(), statSort, viewStr, activeSplit,
                        activePick, ""));
        for (LeaderboardSplit.Variant variant : picked.variants()) {
            appendTab(cmd, events, "#VariantBar", variant.label(), variant.key(), variant.key().equals(activeVariant),
                    false, binding("variant", activePrimary, activeSecondary, sort.name(), statSort, viewStr,
                            activeSplit, activePick, variant.key()));
        }
    }

    /** A dropdown entry's words: a bare translation as its key, anything else as its plain characters. */
    @Nonnull
    private static LocalizableString entryLabel(@Nonnull Message name) {
        return UiText.isClientResolvable(name) ? LocalizableString.fromMessageId(name.getMessageId())
                : LocalizableString.fromString(UiText.flatten(name));
    }

    /** The sort and the view, on a board with a Rankings view; a stats-only board shows neither. */
    private void paintControls(@Nonnull UICommandBuilder cmd, @Nonnull UIEventBuilder events,
                               @Nonnull LeaderboardScreenMessages t) {
        boolean controls = !deps.isStatsOnly();
        cmd.set("#ControlBar.Visible", controls);
        if (!controls) {
            return;
        }
        String viewStr = statsView ? "stats" : "rankings";
        segment(cmd, events, "#SortBest", t.sortScore(), !statsView && sort == SortMode.BEST_SCORE,
                binding("sort", activePrimary, activeSecondary, SortMode.BEST_SCORE.name(), statSort, viewStr));
        segment(cmd, events, "#SortTotal", t.sortTotal(), !statsView && sort == SortMode.TOTAL_POINTS,
                binding("sort", activePrimary, activeSecondary, SortMode.TOTAL_POINTS.name(), statSort, viewStr));
        segment(cmd, events, "#SortTime", t.sortTime(), !statsView && sort == SortMode.BEST_TIME,
                binding("sort", activePrimary, activeSecondary, SortMode.BEST_TIME.name(), statSort, viewStr));
        cmd.set("#SortGroup.Visible", !statsView);
        optional(cmd, "#SortLabel", t.sortLabel());

        segment(cmd, events, "#ViewRankings", t.viewRankings(), !statsView,
                binding("view", activePrimary, activeSecondary, sort.name(), statSort, "rankings"));
        segment(cmd, events, "#ViewStats", t.viewStats(), statsView,
                binding("view", activePrimary, activeSecondary, sort.name(), statSort, "stats"));
        cmd.set("#ViewGroup.Visible", !columns().isEmpty());
        optional(cmd, "#ViewLabel", t.viewLabel());
    }

    /** One of the document's own segments, painted on or off and bound. */
    private void segment(@Nonnull UICommandBuilder cmd, @Nonnull UIEventBuilder events, @Nonnull String selector,
                         @Nonnull Message label, boolean on, @Nonnull EventData binding) {
        SegmentPainter.set(cmd, selector, label, on, playerRef);
        events.addEventBinding(CustomUIEventBindingType.Activating, selector, binding, false);
    }

    private void buildTabRow(@Nonnull UICommandBuilder cmd, @Nonnull UIEventBuilder events,
                             @Nonnull LeaderboardScreenMessages t, @Nonnull String container,
                             @Nonnull List<LeaderboardBucketTab> tabs, @Nonnull String active,
                             @Nonnull String action, boolean views) {
        String viewStr = statsView ? "stats" : "rankings";
        // Append a synthesized trailing "All" tab to any axis with >= 2 concrete tabs (the default
        // selection stays the first concrete tab, so existing behavior + deep-links are unchanged).
        List<LeaderboardBucketTab> display = new ArrayList<>(tabs);
        if (hasAllTab(tabs, deps.allTabs())) {
            display.add(new LeaderboardBucketTab(ALL, t.filterAll()));
        }
        for (LeaderboardBucketTab tab : display) {
            boolean primaryRow = action.equals("group");
            String group = primaryRow ? tab.bucketKey() : activePrimary;
            String bucket = primaryRow ? activeSecondary : tab.bucketKey();
            // Every tab's label goes on a .TextSpans, so a season run's name and year substitute either way. A new
            // primary tab starts on its split's first choice; a period keeps the split's state.
            appendTab(cmd, events, container, tab.label(), tab.bucketKey(), tab.bucketKey().equals(active), views,
                    binding(action, group, bucket, sort.name(), statSort, viewStr, primaryRow ? "" : activeSplit,
                            primaryRow ? "" : activePick, primaryRow ? "" : activeVariant));
        }
        if (!action.equals("group")) {
            return;
        }
        // The primary row's links come last, each a tab that opens another board's page while it has one.
        for (LeaderboardPageDeps.PageLink link : deps.primaryLinks()) {
            if (target(link) == null) {
                continue;
            }
            appendTab(cmd, events, container, link.label(), link.key(), false, views,
                    binding(LINK, link.key(), activeSecondary, sort.name(), statSort, viewStr));
        }
    }

    /**
     * One tab: a view tab with its picture on a row of views, else a two-line segment, so a long name (a season run's
     * name and year) wraps rather than ending in an ellipsis.
     */
    private void appendTab(@Nonnull UICommandBuilder cmd, @Nonnull UIEventBuilder events, @Nonnull String host,
                           @Nonnull Message label, @Nonnull String key, boolean on, boolean views,
                           @Nonnull EventData binding) {
        if (views) {
            ViewTabPainter.append(cmd, events, host, label, Picture.item(deps.primaryPicture(key)), on, binding);
        } else {
            SegmentPainter.appendTwoLine(cmd, events, host, label, on, false, false, binding);
        }
    }

    /** The page {@code link} opens now, or null when it has none (or its builder failed). */
    @Nullable
    private static LeaderboardPageDeps target(@Nonnull LeaderboardPageDeps.PageLink link) {
        try {
            return link.opens().get();
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ==================== the list ====================

    /** The selected buckets' entries, ranked by the Rankings sort. */
    @Nonnull
    private List<Map.Entry<UUID, LeaderboardEntry>> rankingsSorted() {
        List<Map.Entry<UUID, LeaderboardEntry>> sorted =
                new ArrayList<>(deps.board().forBuckets(effectiveBuckets()).entrySet());
        sorted.sort(sort.comparator());
        return sorted;
    }

    /**
     * The selected buckets' entries (a concrete difficulty+size, an "All" axis roll-up, or All+All for the
     * full lifetime view), ranked by the active stat column, a tie broken by the other shown columns from the
     * left.
     */
    @Nonnull
    private List<Map.Entry<UUID, LeaderboardEntry>> statsSorted() {
        List<StatColumnDef> cols = columns();
        int shown = shownColumns();
        List<Map.Entry<UUID, LeaderboardEntry>> sorted =
                new ArrayList<>(deps.board().forBuckets(effectiveBuckets()).entrySet());
        if (deps.isStatsOnly()) {
            // A board of bare tallies holds a player for any stat; this tab lists only those with one of its own.
            sorted.removeIf(en -> !holdsAny(en.getValue(), cols, shown));
        }
        StatColumnDef ranked = columnOf(cols, statSort);
        Comparator<Map.Entry<UUID, LeaderboardEntry>> order = ranked != null ? byColumn(ranked)
                : SortMode.byStat(statSort, false);
        for (int c = 0; c < shown; c++) {
            if (cols.get(c) != ranked) {
                order = order.thenComparing(byColumn(cols.get(c)));
            }
        }
        sorted.sort(order);
        return sorted;
    }

    /** The column keyed {@code key}, or null for the reserved {@code "plays"} and {@code "total"}. */
    @Nullable
    private static StatColumnDef columnOf(@Nonnull List<StatColumnDef> cols, @Nonnull String key) {
        for (StatColumnDef c : cols) {
            if (c.statKey().equals(key)) {
                return c;
            }
        }
        return null;
    }

    /**
     * Ranks by {@code col}'s value ({@link StatColumnDef#valueOf}, a sum read as it is asked): highest first, or
     * lowest first for a best that shrinks, where no value (0) sorts after every real one.
     */
    @Nonnull
    private static Comparator<Map.Entry<UUID, LeaderboardEntry>> byColumn(@Nonnull StatColumnDef col) {
        if (col.ascending()) {
            return Comparator.comparingLong((Map.Entry<UUID, LeaderboardEntry> e) -> {
                long value = col.valueOf(e.getValue());
                return value > 0L ? value : Long.MAX_VALUE;
            });
        }
        return Comparator.comparingLong((Map.Entry<UUID, LeaderboardEntry> e) -> col.valueOf(e.getValue()))
                .reversed();
    }

    /** Where {@code uuid} stands in {@code sorted}, or -1 when they are not on it. */
    private static int indexOf(@Nonnull List<Map.Entry<UUID, LeaderboardEntry>> sorted, @Nonnull UUID uuid) {
        for (int i = 0; i < sorted.size(); i++) {
            if (sorted.get(i).getKey().equals(uuid)) {
                return i;
            }
        }
        return -1;
    }

    /** Whether {@code e} holds a value in any of the first {@code shown} columns. */
    private static boolean holdsAny(@Nonnull LeaderboardEntry e, @Nonnull List<StatColumnDef> cols, int shown) {
        for (int c = 0; c < shown; c++) {
            if (cols.get(c).valueOf(e) != 0L) {
                return true;
            }
        }
        return false;
    }

    /** The Rankings header, the sorted column's name in gold. */
    private void paintRankingsHeader(@Nonnull UICommandBuilder cmd, @Nonnull LeaderboardScreenMessages t) {
        cmd.set("#HdrRank.TextSpans", t.colRank());
        cmd.set("#HdrPlayer.TextSpans", t.colPlayer());
        cmd.set("#HdrTotal.TextSpans", t.colTotal());
        cmd.set("#HdrScore.TextSpans", t.colScore());
        cmd.set("#HdrTime.TextSpans", t.colTime());
        cmd.set("#HdrPlays.TextSpans", t.colPlays());
        String sorted = switch (sort) {
            case BEST_SCORE -> "#HdrScore";
            case TOTAL_POINTS -> "#HdrTotal";
            case BEST_TIME -> "#HdrTime";
        };
        cmd.set(sorted + ".Style.TextColor", ZigTokens.ACCENT);
    }

    private void paintRankingRows(@Nonnull UICommandBuilder cmd,
                                  @Nonnull List<Map.Entry<UUID, LeaderboardEntry>> sorted, int self) {
        int max = Math.min(sorted.size(), MAX_ROWS);
        for (int i = 0; i < max; i++) {
            Map.Entry<UUID, LeaderboardEntry> en = sorted.get(i);
            LeaderboardEntry e = en.getValue();
            cmd.append("#LeaderboardList", ROW_TEMPLATE);
            String sel = "#LeaderboardList[" + i + "]";
            // The name is the library's display name (a title the player shows, when one is), a
            // parameterized Message, so it goes on the Label's TextSpans like every other cell.
            cmd.set(sel + " #Rank.TextSpans", Msg.num(i + 1));
            cmd.set(sel + " #Player.TextSpans", PlayerDisplayNames.displayName(en.getKey(), e.name));
            cmd.set(sel + " #Total.TextSpans", Msg.num(e.totalPoints()));
            cmd.set(sel + " #Score.TextSpans", Msg.num(e.bestScore));
            cmd.set(sel + " #Time.TextSpans", time(e.bestTimeSeconds));
            cmd.set(sel + " #Plays.TextSpans", Msg.num(e.plays));
            paintPlace(cmd, sel, i + 1, i == self, RANKING_CELLS);
        }
    }

    /**
     * The Stats header: each shown column a sort button (the sorted one on the view tab's chosen look, its name
     * gold, its mark showing) with a tooltip saying what it counts, then Plays on a board that records plays.
     */
    private void paintStatsHeader(@Nonnull UICommandBuilder cmd, @Nonnull UIEventBuilder events,
                                  @Nonnull LeaderboardScreenMessages t) {
        cmd.set("#SHdrRank.TextSpans", t.colRank());
        cmd.set("#SHdrPlayer.TextSpans", t.colPlayer());
        List<StatColumnDef> cols = columns();
        int shown = shownColumns();
        for (int c = 0; c < StatColumnDef.MAX_STAT_COLUMNS; c++) {
            String hdr = "#SHdrCol" + c;
            if (c < shown) {
                StatColumnDef col = cols.get(c);
                sortHeader(cmd, events, hdr, col.label(), columnTip(col.label(), t), col.statKey());
            } else {
                cmd.set(hdr + ".Visible", false);
            }
        }
        // The Plays column is sortable too (reserved "plays" key); a stats-only board records no play.
        if (deps.isStatsOnly()) {
            cmd.set("#SHdrPlays.Visible", false);
        } else {
            sortHeader(cmd, events, "#SHdrPlays", t.colPlays(), columnTip(t.colPlays(), t), "plays");
        }
    }

    /** One sort header: its words, its tooltip, its chosen look when it is the sorted column, and its click. */
    private void sortHeader(@Nonnull UICommandBuilder cmd, @Nonnull UIEventBuilder events, @Nonnull String header,
                            @Nonnull Message label, @Nonnull Message tip, @Nonnull String statKey) {
        ZigRichButton.text(cmd, header, label);
        UiText.setTooltip(cmd, header, tip);
        if (statSort.equals(statKey)) {
            ZigStyles.apply(cmd, header + ".Style", ZigStyles.Name.VIEW_TAB_ON, playerRef);
            ZigRichButton.color(cmd, header, ZigTokens.ACCENT);
            cmd.set(header + " #Mark.Visible", true);
        }
        events.addEventBinding(CustomUIEventBindingType.Activating, header,
                binding("statsort", activePrimary, activeSecondary, sort.name(), statKey, "stats"), false);
    }

    /**
     * A column header's tooltip: the column's own label key with {@link #TIP_SUFFIX} when the server ships that
     * key (a stat's "what this counts" line), else the board's line on how a click sorts. Written through
     * {@link UiText#setTooltip}, which puts a line with params on {@code .TooltipTextSpans}.
     */
    @Nonnull
    static Message columnTip(@Nonnull Message label, @Nonnull LeaderboardScreenMessages t) {
        String id = label.getMessageId();
        if (id != null && !id.isBlank() && LangCatalog.has(id + TIP_SUFFIX)) {
            return Message.translation(id + TIP_SUFFIX);
        }
        return t.sortTip();
    }

    private void paintStatsRows(@Nonnull UICommandBuilder cmd, @Nonnull LeaderboardScreenMessages t,
                                @Nonnull List<Map.Entry<UUID, LeaderboardEntry>> sorted, int self) {
        List<StatColumnDef> cols = columns();
        int shown = shownColumns();
        int max = Math.min(sorted.size(), MAX_ROWS);
        Map<UUID, LeaderboardEntry> allTime = metaColumn == null ? Map.of()
                : deps.board().forBuckets(bucketsFor(split.periods().allTimeTab()));
        for (int i = 0; i < max; i++) {
            Map.Entry<UUID, LeaderboardEntry> en = sorted.get(i);
            LeaderboardEntry e = en.getValue();
            cmd.append("#LeaderboardList", STATS_ROW_TEMPLATE);
            String sel = "#LeaderboardList[" + i + "]";
            cmd.set(sel + " #Rank.TextSpans", Msg.num(i + 1));
            cmd.set(sel + " #Player.TextSpans",
                    withMeta(PlayerDisplayNames.displayName(en.getKey(), e.name), allTime.get(en.getKey())));
            if (deps.isStatsOnly()) {
                cmd.set(sel + " #Plays.Visible", false);
            } else {
                cmd.set(sel + " #Plays.TextSpans", Msg.num(e.plays));
            }
            for (int c = 0; c < StatColumnDef.MAX_STAT_COLUMNS; c++) {
                String cell = sel + " #Col" + c;
                if (c < shown) {
                    StatColumnDef def = cols.get(c);
                    cmd.set(cell + ".TextSpans", cell(def, def.valueOf(e), t));
                } else {
                    cmd.set(cell + ".Visible", false);
                }
            }
            paintPlace(cmd, sel, i + 1, i == self, STATS_CELLS);
        }
    }

    /**
     * A row's name with the selection's all-time figure beside it, muted ({@link #metaColumn}, worded by the split's
     * {@link LeaderboardSplit.Periods#words}), joined with {@link Msg#cat} so the name keeps its own parameters; the
     * name alone with no figure to show, no all-time entry to read it from, or words that fail.
     */
    @Nonnull
    private Message withMeta(@Nonnull Message name, @Nullable LeaderboardEntry allTime) {
        if (metaColumn == null || allTime == null) {
            return name;
        }
        try {
            Message words = split.periods().words().apply(metaColumn.valueOf(allTime));
            return words == null ? name : Msg.cat(name, Msg.raw(META_GAP), Msg.color(words, ZigTokens.INK_MUTED));
        } catch (RuntimeException ex) {
            return name;
        }
    }

    /**
     * A stat cell's words: "-" for a low-wins best with no value (no record, as a missing time reads), a
     * playtime or a moment in the reader's words when the board words one ("12h 30m", "3 days ago"), a count as a
     * typed number the client groups, else the column's own format. Every cell and the viewer's strip read it.
     */
    @Nonnull
    private static Message cell(@Nonnull StatColumnDef def, long value, @Nonnull LeaderboardScreenMessages t) {
        if (def.ascending() && value <= 0) {
            return Message.raw("-");
        }
        Message words = switch (def.format()) {
            case DURATION -> t.duration(value);
            case SINCE -> t.since(value);
            default -> null;
        };
        if (words != null) {
            return words;
        }
        if (def.format() == ColumnFormat.GROUPED) {
            return Msg.num(value);
        }
        return Message.raw(def.format().render(value));
    }

    /** A best time as m:ss, or "-" with no winning time. */
    @Nonnull
    private static Message time(int seconds) {
        return Message.raw(seconds > 0 ? ColumnFormat.TIME.render(seconds) : "-");
    }

    /**
     * A row's place: the trophy and the number gold, silver or bronze for the top three, and the viewer's own
     * row on the selected row's fill with every word bright (the medal inks do not read on it).
     */
    private static void paintPlace(@Nonnull UICommandBuilder cmd, @Nonnull String row, int rank, boolean self,
                                   @Nonnull List<String> cells) {
        Medal medal = Medal.of(rank);
        if (medal != null) {
            cmd.set(row + " #Medal.Visible", true);
            UiRetint.retintColor(cmd, row + " #Medal", medal.colour);
        }
        if (self) {
            UiRetint.fill(cmd, row, ZigTokens.SURFACE_ROW_SELECTED);
            cmd.set(row + " #Rank.Style.TextColor", ZigTokens.INK_BRIGHT);
            for (String cell : cells) {
                cmd.set(row + " " + cell + ".Style.TextColor", ZigTokens.INK_BRIGHT);
            }
        } else if (medal != null) {
            cmd.set(row + " #Rank.Style.TextColor", medal.colour);
        }
    }

    // ==================== the viewer's strip ====================

    /**
     * The viewer's own strip: their place, the count on the board and their figure in each column; or, with no
     * place yet, the line saying so and the tab's line on how to get on.
     */
    private void paintYou(@Nonnull UICommandBuilder cmd, @Nonnull LeaderboardScreenMessages t,
                          @Nonnull List<Map.Entry<UUID, LeaderboardEntry>> sorted, int self) {
        cmd.set("#YouHeading.TextSpans", t.youHeading());
        boolean ranked = self >= 0;
        cmd.set("#YouFigures.Visible", ranked);
        if (!ranked) {
            cmd.set("#YourRank.TextSpans", t.yourRankNone());
            optional(cmd, "#YouHint", deps.primaryHint(activePrimary));
            return;
        }
        cmd.set("#YouHint.Visible", false);
        cmd.set("#YourRank.TextSpans", t.youPlayers(sorted.size()));
        int rank = self + 1;
        StatPainter.paint(cmd, "#YouPlace", new Stat(t.youPlace(rank), t.youPlaceLabel(),
                rank == 1 ? Tone.COLLECT : Tone.NEUTRAL));
        LeaderboardEntry mine = sorted.get(self).getValue();
        List<Stat> figures = statsView ? statFigures(t, mine) : rankingFigures(t, mine);
        for (int i = 0; i < YOU_FIGURES; i++) {
            String figure = "#YouStat" + i;
            boolean shown = i < figures.size();
            cmd.set(figure + ".Visible", shown);
            if (shown) {
                StatPainter.paint(cmd, figure, figures.get(i));
            }
        }
    }

    /** The viewer's figure in each shown Stats column, under the column's own name. */
    @Nonnull
    private List<Stat> statFigures(@Nonnull LeaderboardScreenMessages t, @Nonnull LeaderboardEntry mine) {
        List<StatColumnDef> cols = columns();
        int shown = shownColumns();
        List<Stat> out = new ArrayList<>(shown);
        for (int c = 0; c < shown; c++) {
            StatColumnDef def = cols.get(c);
            out.add(new Stat(cell(def, def.valueOf(mine), t), def.label(), Tone.NEUTRAL));
        }
        return out;
    }

    /** The viewer's Rankings figures: total, best, fastest and plays, under the Rankings view's own names. */
    @Nonnull
    private static List<Stat> rankingFigures(@Nonnull LeaderboardScreenMessages t, @Nonnull LeaderboardEntry mine) {
        return List.of(
                new Stat(Msg.num(mine.totalPoints()), t.colTotal(), Tone.NEUTRAL),
                new Stat(Msg.num(mine.bestScore), t.colScore(), Tone.NEUTRAL),
                new Stat(time(mine.bestTimeSeconds), t.colTime(), Tone.NEUTRAL),
                new Stat(Msg.num(mine.plays), t.colPlays(), Tone.NEUTRAL));
    }

    // ==================== shared ====================

    /** A label shown with {@code text} on its {@code .TextSpans}, or hidden when there is none. */
    private static void optional(@Nonnull UICommandBuilder cmd, @Nonnull String label, @Nullable Message text) {
        if (text != null) {
            cmd.set(label + ".TextSpans", text);
        }
        cmd.set(label + ".Visible", text != null);
    }

    /** The event a control carries: the page's full NEXT state, the split's as it stands. */
    @Nonnull
    private EventData binding(@Nonnull String action, @Nonnull String group, @Nonnull String bucket,
                              @Nonnull String sortName, @Nonnull String statSortName, @Nonnull String view) {
        return binding(action, group, bucket, sortName, statSortName, view, activeSplit, activePick, activeVariant);
    }

    /**
     * The event a control carries: the page's full NEXT state (no duplicate keys). A null {@code pick} writes no
     * Pick, for the picker, whose live value comes back under {@link #PICK_ECHO} instead.
     */
    @Nonnull
    private static EventData binding(@Nonnull String action, @Nonnull String group, @Nonnull String bucket,
                                     @Nonnull String sortName, @Nonnull String statSortName, @Nonnull String view,
                                     @Nonnull String splitKey, @Nullable String pick, @Nonnull String variant) {
        EventData data = EventData.of("Action", action)
                .append("Group", group)
                .append("Bucket", bucket)
                .append("Sort", sortName)
                .append("StatSort", statSortName)
                .append("View", view)
                .append("Split", splitKey)
                .append("Variant", variant);
        return pick == null ? data : data.append("Pick", pick);
    }

    @Override
    public void handleDataEvent(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store,
                                @Nonnull LeaderboardEventData data) {
        Player player = (Player) store.getComponent(ref, Player.getComponentType());
        if (player == null) {
            return;
        }
        // A rail click carries the row's index and no Action: it is answered here, opened or refused,
        // before the missing action below would close the page.
        if (rail.handle(data.menu, store, ref, player, this::answer)) {
            return;
        }
        if (data.action == null || "close".equals(data.action)) {
            player.getPageManager().setPage(ref, store, Page.None);
            return;
        }
        if (LINK.equals(data.action)) {
            LeaderboardPageDeps opened = null;
            for (LeaderboardPageDeps.PageLink link : deps.primaryLinks()) {
                if (link.key().equals(data.group)) {
                    opened = target(link);
                    break;
                }
            }
            if (opened == null) {
                answer();
            } else {
                player.getPageManager().openCustomPage(ref, store, new LeaderboardPage(playerRef, opened));
            }
            return;
        }
        String primary = data.group;
        String secondary = data.bucket;
        SortMode nextSort = parseSort(data.sort);
        boolean nextStats = "stats".equals(data.view);
        player.getPageManager().openCustomPage(ref, store, new LeaderboardPage(playerRef, deps, primary, secondary,
                nextSort, data.statSort, nextStats, data.split, data.pick, data.variant));
    }

    /** An empty update, for a rail click nothing opened for: the client always hears back. */
    private void answer() {
        sendUpdate(new UICommandBuilder(), new UIEventBuilder(), false);
    }

    @Nonnull
    private SortMode parseSort(@Nullable String name) {
        if (name != null) {
            try {
                return SortMode.valueOf(name);
            } catch (IllegalArgumentException ignored) {
                // fall through to current
            }
        }
        return sort;
    }
}
