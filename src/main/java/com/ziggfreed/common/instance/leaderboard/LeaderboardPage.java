package com.ziggfreed.common.instance.leaderboard;

import java.util.ArrayList;
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
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import com.ziggfreed.common.util.NumberFormatter;

/**
 * The generic leaderboard page (the reusable lift of {@code KweebecLeaderboardPage}). Two tab
 * axes (an optional PRIMARY axis - difficulty - over the SECONDARY axis - party size), a
 * three-metric sort toggle (best score / total points / best time), a Rankings/Stats view
 * toggle, a scrollable ranked list (top three gold/silver/bronze, the viewer's row highlighted),
 * and a "your rank" footer. The {@code ResultsPage} CTA deep-links here to the just-played bucket.
 *
 * <p>Consumer policy (the {@link Leaderboard} for the mode, the tab axes, the stat columns, the
 * chrome) is supplied through {@link LeaderboardPageDeps}; the page is mod-agnostic. When the
 * primary axis / stat columns are empty it degrades to the original single-axis, score-only board.
 * The page is stateless across events: each interaction reopens it with the next
 * (primary, secondary, sort, statSort, view) state carried on every event binding. Every
 * {@code handleDataEvent} exit path sends a response.
 *
 * <p>Each tab axis with two or more concrete tabs synthesizes a trailing "All" tab (reserved
 * bucket key {@code "*"}) that aggregates every concrete bucket on that axis, so All+All is the
 * full lifetime roll-up. The Stats view honors the tab selection and is sortable by each stat
 * column (and Plays) via clickable column headers; the Rankings view keeps its three-metric toggle.
 */
public class LeaderboardPage extends InteractiveCustomUIPage<LeaderboardEventData> {

    private static final String PAGE_TEMPLATE = "Pages/ZigLeaderboardPage.ui";
    private static final String ROW_TEMPLATE = "Pages/ZigLeaderboardRow.ui";
    private static final String STATS_ROW_TEMPLATE = "Pages/ZigLeaderboardStatsRow.ui";
    private static final String TAB_TEMPLATE = "Pages/ZigLeaderboardTab.ui";
    private static final int MAX_ROWS = 100;
    // Strong active/inactive contrast so the current filter selection is obvious.
    private static final String ACTIVE_TINT = "#5e86bd";
    private static final String ACTIVE_HOVER = "#6f97cf";
    private static final String ACTIVE_TEXT = "#ffffff";
    private static final String INACTIVE_TINT = "#2f3b49";
    private static final String INACTIVE_HOVER = "#445364";
    private static final String INACTIVE_TEXT = "#9fb0c2";
    // Synthesized "All" tab: a reserved bucket key (no real preset id / party size contains it),
    // appended to the end of any axis with >= 2 concrete tabs; selecting it aggregates that axis.
    private static final String ALL = "*";

    private final LeaderboardPageDeps deps;
    private final String activePrimary; // "" when there is no primary axis
    private final String activeSecondary;
    private final SortMode sort;
    private final String statSort; // the active Stats-view column metric ("" when no stat columns)
    private final boolean statsView;

    public LeaderboardPage(@Nonnull PlayerRef playerRef, @Nonnull LeaderboardPageDeps deps) {
        this(playerRef, deps, null);
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
        super(playerRef, CustomPageLifetime.CanDismissOrCloseThroughInteraction, LeaderboardEventData.CODEC);
        this.deps = deps;
        this.activePrimary = resolveKey(deps.primaryTabs(), primary);
        this.activeSecondary = resolveKey(deps.tabs(), secondary);
        this.sort = sort != null ? sort : SortMode.BEST_SCORE;
        this.statSort = resolveStatKey(deps, statSort);
        this.statsView = statsView && !deps.statColumns().isEmpty();
    }

    @Nonnull
    private static String resolveKey(@Nonnull List<LeaderboardBucketTab> tabs, @Nullable String key) {
        if (ALL.equals(key) && hasAllTab(tabs)) {
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

    /** An axis synthesizes a trailing "All" tab only when it has two or more concrete tabs. */
    private static boolean hasAllTab(@Nonnull List<LeaderboardBucketTab> tabs) {
        return tabs.size() >= 2;
    }

    /**
     * Resolve the active Stats-view sort key: a valid stat-column key, the reserved {@code "plays"}
     * or {@code "total"}, else the first stat column (the default). An empty stat-column list yields
     * {@code ""} (the Stats view is disabled in that case anyway).
     */
    @Nonnull
    private static String resolveStatKey(@Nonnull LeaderboardPageDeps deps, @Nullable String key) {
        if (key != null && !key.isBlank()) {
            if ("plays".equals(key) || "total".equals(key)) {
                return key;
            }
            for (StatColumnDef c : deps.statColumns()) {
                if (c.statKey().equals(key)) {
                    return key;
                }
            }
        }
        return deps.statColumns().isEmpty() ? "" : deps.statColumns().get(0).statKey();
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
     * key on that axis. With both axes present the result is their cross product
     * ({@code "<primary>_<secondary>"}); with only one axis it is that axis's keys directly.
     * {@link Leaderboard#forBuckets} merges the set, so a single concrete bucket, an "All" axis
     * roll-up, and the All+All lifetime view all read the same way and stay consistent between the
     * Rankings and Stats views.
     */
    @Nonnull
    private List<String> effectiveBuckets() {
        List<String> primaries = ALL.equals(activePrimary) ? concreteKeys(deps.primaryTabs()) : List.of(activePrimary);
        List<String> secondaries = ALL.equals(activeSecondary) ? concreteKeys(deps.tabs()) : List.of(activeSecondary);
        if (deps.primaryTabs().isEmpty()) {
            return secondaries; // single secondary axis (legacy board)
        }
        if (deps.tabs().isEmpty()) {
            return primaries; // single primary axis (e.g. PvP mode)
        }
        List<String> out = new ArrayList<>(primaries.size() * secondaries.size());
        for (String p : primaries) {
            for (String s : secondaries) {
                out.add(p + "_" + s);
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
        cmd.append(PAGE_TEMPLATE);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#CloseButton", EventData.of("Action", "close"));

        cmd.set("#Title.Text", t.title());
        boolean hasStats = !deps.statColumns().isEmpty();

        // Leading row labels (optional; clarify what each selector row drives).
        setLabel(cmd, "#GroupLabel", t.primaryAxisLabel());
        setLabel(cmd, "#TabLabel", t.secondaryAxisLabel());
        setLabel(cmd, "#SortLabel", t.sortLabel());
        setLabel(cmd, "#ViewLabel", t.viewLabel());

        // Tab axes (each axis with >= 2 concrete tabs gets a synthesized trailing "All" tab).
        buildTabRow(cmd, events, t, "#GroupBar", deps.primaryTabs(), activePrimary, "group");
        buildTabRow(cmd, events, t, "#TabBar", deps.tabs(), activeSecondary, "tab");
        cmd.set("#GroupRow.Visible", !deps.primaryTabs().isEmpty());

        // Sort toggle (Rankings only) + view toggle.
        String viewStr = statsView ? "stats" : "rankings";
        cmd.set("#SortBest.Text", t.sortScore());
        cmd.set("#SortTotal.Text", t.sortTotal());
        cmd.set("#SortTime.Text", t.sortTime());
        bind(events, "#SortBest", "sort", activePrimary, activeSecondary, SortMode.BEST_SCORE.name(), statSort, viewStr);
        bind(events, "#SortTotal", "sort", activePrimary, activeSecondary, SortMode.TOTAL_POINTS.name(), statSort, viewStr);
        bind(events, "#SortTime", "sort", activePrimary, activeSecondary, SortMode.BEST_TIME.name(), statSort, viewStr);
        style(cmd, "#SortBest", !statsView && sort == SortMode.BEST_SCORE);
        style(cmd, "#SortTotal", !statsView && sort == SortMode.TOTAL_POINTS);
        style(cmd, "#SortTime", !statsView && sort == SortMode.BEST_TIME);
        cmd.set("#SortGroup.Visible", !statsView);
        cmd.set("#SortLabel.Visible", !statsView && t.sortLabel() != null);

        cmd.set("#ViewRankings.Text", t.viewRankings());
        cmd.set("#ViewStats.Text", t.viewStats());
        bind(events, "#ViewRankings", "view", activePrimary, activeSecondary, sort.name(), statSort, "rankings");
        bind(events, "#ViewStats", "view", activePrimary, activeSecondary, sort.name(), statSort, "stats");
        cmd.set("#ViewGroup.Visible", hasStats);
        cmd.set("#ViewLabel.Visible", hasStats && t.viewLabel() != null);
        style(cmd, "#ViewRankings", !statsView);
        style(cmd, "#ViewStats", statsView);

        if (statsView) {
            buildStats(cmd, events, t);
        } else {
            buildRankings(cmd, t);
        }
    }

    // ==================== rankings view ====================

    private void buildRankings(@Nonnull UICommandBuilder cmd, @Nonnull LeaderboardScreenMessages t) {
        cmd.set("#TableHeader.Visible", true);
        cmd.set("#StatsHeader.Visible", false);
        cmd.set("#HdrRank.Text", t.colRank());
        cmd.set("#HdrPlayer.Text", t.colPlayer());
        cmd.set("#HdrTotal.Text", t.colTotal());
        cmd.set("#HdrScore.Text", t.colScore());
        cmd.set("#HdrTime.Text", t.colTime());
        cmd.set("#HdrPlays.Text", t.colPlays());

        Map<UUID, LeaderboardEntry> bucket = deps.board().forBuckets(effectiveBuckets());
        List<Map.Entry<UUID, LeaderboardEntry>> sorted = new ArrayList<>(bucket.entrySet());
        sorted.sort(sort.comparator());

        UUID self = playerRef.getUuid();
        if (sorted.isEmpty()) {
            cmd.set("#EmptyState.Text", t.empty());
            cmd.set("#EmptyState.Visible", true);
        } else {
            int max = Math.min(sorted.size(), MAX_ROWS);
            for (int i = 0; i < max; i++) {
                Map.Entry<UUID, LeaderboardEntry> en = sorted.get(i);
                appendRankRow(cmd, i, i + 1, en.getKey(), en.getValue(), en.getKey().equals(self));
            }
        }
        setYourRank(cmd, t, sorted, self);
    }

    private void appendRankRow(@Nonnull UICommandBuilder cmd, int index, int rank,
                               @Nonnull UUID uuid, @Nonnull LeaderboardEntry e, boolean isSelf) {
        cmd.append("#LeaderboardList", ROW_TEMPLATE);
        String sel = "#LeaderboardList[" + index + "]";
        // Plain data (rank / name / numbers) is a plain String, NOT a raw Message: a Label's .Text
        // accepts a string (and resolves a translation key) but cannot wrap a raw-Message object,
        // which would abort the whole CustomUI update. Numbers + a proper-noun username are data.
        cmd.set(sel + " #Rank.Text", "#" + rank);
        cmd.set(sel + " #Player.Text", resolveName(uuid, e));
        cmd.set(sel + " #Total.Text", NumberFormatter.grouped(e.totalPoints));
        cmd.set(sel + " #Score.Text", NumberFormatter.grouped(e.bestScore));
        cmd.set(sel + " #Time.Text", e.bestTimeSeconds > 0 ? formatTime(e.bestTimeSeconds) : "-");
        cmd.set(sel + " #Plays.Text", Integer.toString(e.plays));
        paintRank(cmd, sel, rank, isSelf);
    }

    // ==================== stats view ====================

    private void buildStats(@Nonnull UICommandBuilder cmd, @Nonnull UIEventBuilder events,
                            @Nonnull LeaderboardScreenMessages t) {
        cmd.set("#TableHeader.Visible", false);
        cmd.set("#StatsHeader.Visible", true);
        cmd.set("#SHdrRank.Text", t.colRank());
        cmd.set("#SHdrPlayer.Text", t.colPlayer());

        // Stat column headers are clickable: a click re-sorts the list by that column's metric and
        // highlights the active header (the same active/inactive tint as the tabs).
        List<StatColumnDef> cols = deps.statColumns();
        int shown = Math.min(cols.size(), StatColumnDef.MAX_STAT_COLUMNS);
        for (int c = 0; c < StatColumnDef.MAX_STAT_COLUMNS; c++) {
            String hdr = "#SHdrCol" + c;
            if (c < shown) {
                String key = cols.get(c).statKey();
                cmd.set(hdr + ".Text", cols.get(c).label());
                cmd.set(hdr + ".Visible", true);
                style(cmd, hdr, statSort.equals(key));
                bindStatSort(events, hdr, key);
            } else {
                cmd.set(hdr + ".Visible", false);
            }
        }
        // The Plays column is sortable too (reserved "plays" key).
        cmd.set("#SHdrPlays.Text", t.colPlays());
        style(cmd, "#SHdrPlays", statSort.equals("plays"));
        bindStatSort(events, "#SHdrPlays", "plays");

        // Aggregate across the SELECTED buckets (honoring the tabs): a concrete difficulty+size, an
        // "All" axis roll-up, or All+All for the full lifetime view. Sorted by the active stat metric.
        Map<UUID, LeaderboardEntry> all = deps.board().forBuckets(effectiveBuckets());
        List<Map.Entry<UUID, LeaderboardEntry>> sorted = new ArrayList<>(all.entrySet());
        sorted.sort(SortMode.byStat(statSort));

        UUID self = playerRef.getUuid();
        if (sorted.isEmpty()) {
            cmd.set("#EmptyState.Text", t.empty());
            cmd.set("#EmptyState.Visible", true);
        } else {
            int max = Math.min(sorted.size(), MAX_ROWS);
            for (int i = 0; i < max; i++) {
                Map.Entry<UUID, LeaderboardEntry> en = sorted.get(i);
                appendStatsRow(cmd, i, i + 1, en.getKey(), en.getValue(), cols, shown, en.getKey().equals(self));
            }
        }
        setYourRank(cmd, t, sorted, self);
    }

    private void appendStatsRow(@Nonnull UICommandBuilder cmd, int index, int rank, @Nonnull UUID uuid,
                                @Nonnull LeaderboardEntry e, @Nonnull List<StatColumnDef> cols, int shown,
                                boolean isSelf) {
        cmd.append("#LeaderboardList", STATS_ROW_TEMPLATE);
        String sel = "#LeaderboardList[" + index + "]";
        cmd.set(sel + " #Rank.Text", "#" + rank);
        cmd.set(sel + " #Player.Text", resolveName(uuid, e));
        cmd.set(sel + " #Plays.Text", Integer.toString(e.plays));
        for (int c = 0; c < StatColumnDef.MAX_STAT_COLUMNS; c++) {
            String cell = sel + " #Col" + c;
            if (c < shown) {
                StatColumnDef def = cols.get(c);
                cmd.set(cell + ".Text", def.format().render(e.stat(def.statKey())));
                cmd.set(cell + ".Visible", true);
            } else {
                cmd.set(cell + ".Visible", false);
            }
        }
        paintRank(cmd, sel, rank, isSelf);
    }

    // ==================== shared rendering ====================

    private void buildTabRow(@Nonnull UICommandBuilder cmd, @Nonnull UIEventBuilder events,
                             @Nonnull LeaderboardScreenMessages t, @Nonnull String container,
                             @Nonnull List<LeaderboardBucketTab> tabs, @Nonnull String active,
                             @Nonnull String action) {
        String viewStr = statsView ? "stats" : "rankings";
        // Append a synthesized trailing "All" tab to any axis with >= 2 concrete tabs (the default
        // selection stays the first concrete tab, so existing behavior + deep-links are unchanged).
        List<LeaderboardBucketTab> display = new ArrayList<>(tabs);
        if (hasAllTab(tabs)) {
            display.add(new LeaderboardBucketTab(ALL, t.filterAll()));
        }
        for (int i = 0; i < display.size(); i++) {
            LeaderboardBucketTab tab = display.get(i);
            cmd.append(container, TAB_TEMPLATE);
            String sel = container + "[" + i + "]";
            cmd.set(sel + " #TabBtn.Text", tab.label());
            style(cmd, sel + " #TabBtn", tab.bucketKey().equals(active));
            String group = action.equals("group") ? tab.bucketKey() : activePrimary;
            String bucket = action.equals("group") ? activeSecondary : tab.bucketKey();
            bind(events, sel + " #TabBtn", action, group, bucket, sort.name(), statSort, viewStr);
        }
    }

    /** Bind a Stats-view column header so a click re-sorts the Stats list by {@code statKey}. */
    private void bindStatSort(@Nonnull UIEventBuilder events, @Nonnull String selector, @Nonnull String statKey) {
        bind(events, selector, "statsort", activePrimary, activeSecondary, sort.name(), statKey, "stats");
    }

    /** Bind a control so its click round-trips the page's full NEXT state (no duplicate keys). */
    private void bind(@Nonnull UIEventBuilder events, @Nonnull String selector, @Nonnull String action,
                      @Nonnull String group, @Nonnull String bucket, @Nonnull String sortName,
                      @Nonnull String statSortName, @Nonnull String view) {
        events.addEventBinding(CustomUIEventBindingType.Activating, selector,
                EventData.of("Action", action)
                        .append("Group", group)
                        .append("Bucket", bucket)
                        .append("Sort", sortName)
                        .append("StatSort", statSortName)
                        .append("View", view),
                false);
    }

    /** Paint a tab/sort/view button as the ACTIVE selection (bright + bold + white) or an inactive one (muted). */
    private static void style(@Nonnull UICommandBuilder cmd, @Nonnull String btnSelector, boolean active) {
        String bg = active ? ACTIVE_TINT : INACTIVE_TINT;
        cmd.set(btnSelector + ".Style.Default.Background.Color", bg);
        cmd.set(btnSelector + ".Style.Hovered.Background.Color", active ? ACTIVE_HOVER : INACTIVE_HOVER);
        cmd.set(btnSelector + ".Style.Pressed.Background.Color", bg);
        cmd.set(btnSelector + ".Style.Default.LabelStyle.TextColor", active ? ACTIVE_TEXT : INACTIVE_TEXT);
        cmd.set(btnSelector + ".Style.Default.LabelStyle.RenderBold", active);
    }

    private static void setLabel(@Nonnull UICommandBuilder cmd, @Nonnull String sel, @Nullable Message label) {
        if (label != null) {
            cmd.set(sel + ".Text", label);
            cmd.set(sel + ".Visible", true);
        } else {
            cmd.set(sel + ".Visible", false);
        }
    }

    private static void paintRank(@Nonnull UICommandBuilder cmd, @Nonnull String rowSel, int rank, boolean isSelf) {
        if (rank == 1) {
            cmd.set(rowSel + " #Rank.Style.TextColor", "#ffd700");
        } else if (rank == 2) {
            cmd.set(rowSel + " #Rank.Style.TextColor", "#c0c0c0");
        } else if (rank == 3) {
            cmd.set(rowSel + " #Rank.Style.TextColor", "#cd7f32");
        }
        if (isSelf) {
            cmd.set(rowSel + ".Background", "#1a3d4a");
        }
    }

    private void setYourRank(@Nonnull UICommandBuilder cmd, @Nonnull LeaderboardScreenMessages t,
                             @Nonnull List<Map.Entry<UUID, LeaderboardEntry>> sorted, @Nonnull UUID self) {
        for (int i = 0; i < sorted.size(); i++) {
            if (sorted.get(i).getKey().equals(self)) {
                long metric = statsView
                        ? SortMode.statMetric(sorted.get(i).getValue(), statSort)
                        : sort.metric(sorted.get(i).getValue());
                cmd.set("#YourRank.Text", t.yourRank(i + 1, metric));
                return;
            }
        }
        cmd.set("#YourRank.Text", t.yourRankNone());
    }

    @Override
    public void handleDataEvent(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store,
                                @Nonnull LeaderboardEventData data) {
        Player player = (Player) store.getComponent(ref, Player.getComponentType());
        if (player == null) {
            return;
        }
        if (data.action == null || "close".equals(data.action)) {
            player.getPageManager().setPage(ref, store, Page.None);
            return;
        }
        String primary = data.group;
        String secondary = data.bucket;
        SortMode nextSort = parseSort(data.sort);
        boolean nextStats = "stats".equals(data.view);
        player.getPageManager().openCustomPage(ref, store,
                new LeaderboardPage(playerRef, deps, primary, secondary, nextSort, data.statSort, nextStats));
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

    @Nonnull
    private static String resolveName(@Nonnull UUID uuid, @Nonnull LeaderboardEntry e) {
        try {
            PlayerRef pr = Universe.get().getPlayer(uuid);
            if (pr != null) {
                String live = pr.getUsername();
                if (live != null && !live.isBlank()) {
                    return live;
                }
            }
        } catch (Throwable ignored) {
        }
        if (e.name != null && !e.name.isBlank()) {
            return e.name;
        }
        return uuid.toString().substring(0, 8);
    }

    @Nonnull
    private static String formatTime(int seconds) {
        int m = seconds / 60;
        int s = seconds % 60;
        return m + ":" + (s < 10 ? "0" + s : Integer.toString(s));
    }
}
