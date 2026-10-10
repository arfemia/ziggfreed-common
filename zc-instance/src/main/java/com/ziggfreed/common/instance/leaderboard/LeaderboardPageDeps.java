package com.ziggfreed.common.instance.leaderboard;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.Message;

/**
 * The immutable consumer-policy bundle a {@link LeaderboardPage} is built from: the
 * {@link Leaderboard} to read (one board per game-mode, selected by the consumer), the
 * optional PRIMARY tab axis ({@code primaryTabs} - e.g. difficulty), the SECONDARY tab axis
 * ({@code tabs} - e.g. party size), the Stats-view {@link StatColumnDef stat columns}, the
 * locale-free {@link LeaderboardScreenMessages} chrome, and how a selection on both axes names its
 * bucket ({@link BucketKeys}). Built once at the consumer's startup; the page stays mod-agnostic.
 *
 * <p>With both axes, the active bucket key is {@link #bucketKey} of the two tab keys, which is
 * {@code primary + "_" + secondary} unless the board composes its own; with one axis it is that
 * axis's tab key as it stands (back-compat with a single-axis board). When {@code primaryTabs} /
 * {@code statColumns} are empty the page renders exactly the original single-axis, score-only board.
 *
 * <p>{@link #menuTab} puts the page on the shared menu's rail with that tab selected
 * ({@link #withMenuTab}); every constructor leaves it null, so a board built without it keeps the page's
 * own frame.
 */
public final class LeaderboardPageDeps {

    /** How a primary and a secondary tab key name the {@link Leaderboard} bucket a selection reads. */
    @FunctionalInterface
    public interface BucketKeys {

        /** The bucket key for {@code primary} crossed with {@code secondary}. */
        @Nonnull
        String compose(@Nonnull String primary, @Nonnull String secondary);
    }

    /** The composition every board had before a board could name its own: {@code "<primary>_<secondary>"}. */
    public static final BucketKeys UNDERSCORE = (primary, secondary) -> primary + "_" + secondary;

    /**
     * A tab at the end of the primary row that opens another board's page instead of selecting a bucket
     * (Records' boss fights beside the stat board's sections). {@code opens} is asked each time the row is
     * painted and again on the click: null hides the tab, or answers the click with nothing opened.
     */
    public record PageLink(@Nonnull String key, @Nonnull Message label,
                           @Nonnull Supplier<LeaderboardPageDeps> opens) {
    }

    private final Leaderboard board;
    private final List<LeaderboardBucketTab> primaryTabs;
    private final List<LeaderboardBucketTab> tabs;
    private final List<StatColumnDef> statColumns;
    private final LeaderboardScreenMessages text;
    private final BucketKeys bucketKeys;
    @Nullable private final String menuTab;
    /** Primary tab key -> that tab's own Stats columns; a tab not in it uses {@link #statColumns}. */
    private final Map<String, List<StatColumnDef>> columnsByPrimary;
    private final boolean allTabs;
    private final boolean statsOnly;
    private final List<PageLink> primaryLinks;

    /** Back-compat: a single-axis, score-only board (no difficulty axis, no stats view). */
    public LeaderboardPageDeps(@Nonnull Leaderboard board, @Nonnull List<LeaderboardBucketTab> tabs,
                               @Nonnull LeaderboardScreenMessages text) {
        this(board, List.of(), tabs, List.of(), text);
    }

    /** Two axes composed {@code "<primary>_<secondary>"} ({@link #UNDERSCORE}). */
    public LeaderboardPageDeps(@Nonnull Leaderboard board, @Nonnull List<LeaderboardBucketTab> primaryTabs,
                               @Nonnull List<LeaderboardBucketTab> tabs, @Nonnull List<StatColumnDef> statColumns,
                               @Nonnull LeaderboardScreenMessages text) {
        this(board, primaryTabs, tabs, statColumns, text, UNDERSCORE);
    }

    /** Two axes composed by the board's own {@code bucketKeys}. */
    public LeaderboardPageDeps(@Nonnull Leaderboard board, @Nonnull List<LeaderboardBucketTab> primaryTabs,
                               @Nonnull List<LeaderboardBucketTab> tabs, @Nonnull List<StatColumnDef> statColumns,
                               @Nonnull LeaderboardScreenMessages text, @Nonnull BucketKeys bucketKeys) {
        this(board, primaryTabs, tabs, statColumns, text, bucketKeys, null, Map.of(), true, false, List.of());
    }

    private LeaderboardPageDeps(@Nonnull Leaderboard board, @Nonnull List<LeaderboardBucketTab> primaryTabs,
                                @Nonnull List<LeaderboardBucketTab> tabs, @Nonnull List<StatColumnDef> statColumns,
                                @Nonnull LeaderboardScreenMessages text, @Nonnull BucketKeys bucketKeys,
                                @Nullable String menuTab, @Nonnull Map<String, List<StatColumnDef>> columnsByPrimary,
                                boolean allTabs, boolean statsOnly, @Nonnull List<PageLink> primaryLinks) {
        this.primaryLinks = List.copyOf(primaryLinks);
        this.board = board;
        this.primaryTabs = List.copyOf(primaryTabs);
        this.tabs = List.copyOf(tabs);
        this.statColumns = List.copyOf(statColumns);
        this.text = text;
        this.bucketKeys = bucketKeys;
        this.menuTab = menuTab;
        Map<String, List<StatColumnDef>> columns = new LinkedHashMap<>();
        for (Map.Entry<String, List<StatColumnDef>> e : columnsByPrimary.entrySet()) {
            columns.put(e.getKey(), List.copyOf(e.getValue()));
        }
        this.columnsByPrimary = Map.copyOf(columns);
        this.allTabs = allTabs;
        this.statsOnly = statsOnly;
    }

    /**
     * A copy that puts the page on the shared menu's rail with {@code tab} selected (a menu entry's id,
     * such as the Records tab's); null puts it back in its own frame. Everything else is kept.
     */
    @Nonnull
    public LeaderboardPageDeps withMenuTab(@Nullable String tab) {
        return new LeaderboardPageDeps(board, primaryTabs, tabs, statColumns, text, bucketKeys, tab,
                columnsByPrimary, allTabs, statsOnly, primaryLinks);
    }

    /**
     * A copy whose primary tabs each carry their own Stats columns ({@code primary tab key -> columns}), as
     * a stat board's sections do; a tab the map leaves out keeps {@link #statColumns()}. Everything else is
     * kept.
     */
    @Nonnull
    public LeaderboardPageDeps withColumnsByPrimary(@Nonnull Map<String, List<StatColumnDef>> columns) {
        return new LeaderboardPageDeps(board, primaryTabs, tabs, statColumns, text, bucketKeys, menuTab,
                columns, allTabs, statsOnly, primaryLinks);
    }

    /**
     * A copy with no synthesized "All" tab on either axis, for axes whose buckets do not add up (a
     * section's columns, a week beside a month). Everything else is kept.
     */
    @Nonnull
    public LeaderboardPageDeps withoutAllTabs() {
        return new LeaderboardPageDeps(board, primaryTabs, tabs, statColumns, text, bucketKeys, menuTab,
                columnsByPrimary, false, statsOnly, primaryLinks);
    }

    /**
     * A copy that shows only the Stats view, with no Rankings view, sort row or Plays column: a board of
     * bare tallies, which never records a play or a best. Everything else is kept.
     */
    @Nonnull
    public LeaderboardPageDeps statsOnly() {
        return new LeaderboardPageDeps(board, primaryTabs, tabs, statColumns, text, bucketKeys, menuTab,
                columnsByPrimary, allTabs, true, primaryLinks);
    }

    /**
     * A copy with one more {@link PageLink} at the end of the primary row: a tab labelled {@code label} that
     * opens the page {@code opens} builds. Everything else is kept.
     */
    @Nonnull
    public LeaderboardPageDeps withPrimaryLink(@Nonnull String key, @Nonnull Message label,
                                               @Nonnull Supplier<LeaderboardPageDeps> opens) {
        List<PageLink> links = new ArrayList<>(primaryLinks);
        links.add(new PageLink(key, label, opens));
        return new LeaderboardPageDeps(board, primaryTabs, tabs, statColumns, text, bucketKeys, menuTab,
                columnsByPrimary, allTabs, statsOnly, links);
    }

    /** The tabs at the end of the primary row that open another page ({@link #withPrimaryLink}). */
    @Nonnull
    public List<PageLink> primaryLinks() {
        return primaryLinks;
    }

    /** Whether an axis of two or more tabs gets the synthesized "All" tab (true unless {@link #withoutAllTabs}). */
    public boolean allTabs() {
        return allTabs;
    }

    /** Whether the page shows the Stats view alone ({@link #statsOnly()}). */
    public boolean isStatsOnly() {
        return statsOnly;
    }

    /** The Stats columns under primary tab {@code primary}: its own when it has some, else {@link #statColumns()}. */
    @Nonnull
    public List<StatColumnDef> statColumns(@Nonnull String primary) {
        List<StatColumnDef> own = columnsByPrimary.get(primary);
        return own != null ? own : statColumns;
    }

    /** The rail tab to select, or null for the page's own frame. */
    @Nullable
    public String menuTab() {
        return menuTab;
    }

    @Nonnull
    public Leaderboard board() {
        return board;
    }

    /** The primary tab axis (difficulty); empty for a single-axis board. */
    @Nonnull
    public List<LeaderboardBucketTab> primaryTabs() {
        return primaryTabs;
    }

    /** The secondary tab axis (party size). */
    @Nonnull
    public List<LeaderboardBucketTab> tabs() {
        return tabs;
    }

    /** The Stats-view columns; empty disables the Stats view. */
    @Nonnull
    public List<StatColumnDef> statColumns() {
        return statColumns;
    }

    @Nonnull
    public LeaderboardScreenMessages text() {
        return text;
    }

    /** The bucket a primary tab crossed with a secondary tab reads. */
    @Nonnull
    public String bucketKey(@Nonnull String primary, @Nonnull String secondary) {
        return bucketKeys.compose(primary, secondary);
    }
}
