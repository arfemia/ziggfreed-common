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
    /** Primary tab or link key -> the item whose picture its tab shows ({@link #withPrimaryPictures}). */
    private final Map<String, String> primaryPictures;
    /** Primary tab key -> the line saying what counts there and how to get on it ({@link #withPrimaryHints}). */
    private final Map<String, Message> primaryHints;
    /** Primary tab key -> that tab's own split of its board ({@link #withSplits}); a tab not in it has none. */
    private final Map<String, LeaderboardSplit> splits;

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
        this(board, primaryTabs, tabs, statColumns, text, bucketKeys, null, Map.of(), true, false, List.of(),
                Map.of(), Map.of(), Map.of());
    }

    private LeaderboardPageDeps(@Nonnull Leaderboard board, @Nonnull List<LeaderboardBucketTab> primaryTabs,
                                @Nonnull List<LeaderboardBucketTab> tabs, @Nonnull List<StatColumnDef> statColumns,
                                @Nonnull LeaderboardScreenMessages text, @Nonnull BucketKeys bucketKeys,
                                @Nullable String menuTab, @Nonnull Map<String, List<StatColumnDef>> columnsByPrimary,
                                boolean allTabs, boolean statsOnly, @Nonnull List<PageLink> primaryLinks,
                                @Nonnull Map<String, String> primaryPictures,
                                @Nonnull Map<String, Message> primaryHints,
                                @Nonnull Map<String, LeaderboardSplit> splits) {
        this.splits = Map.copyOf(splits);
        this.primaryLinks = List.copyOf(primaryLinks);
        this.primaryPictures = Map.copyOf(primaryPictures);
        this.primaryHints = Map.copyOf(primaryHints);
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
                columnsByPrimary, allTabs, statsOnly, primaryLinks, primaryPictures, primaryHints, splits);
    }

    /**
     * A copy whose primary tabs each carry their own Stats columns ({@code primary tab key -> columns}), as
     * a stat board's sections do; a tab the map leaves out keeps {@link #statColumns()}. Everything else is
     * kept.
     */
    @Nonnull
    public LeaderboardPageDeps withColumnsByPrimary(@Nonnull Map<String, List<StatColumnDef>> columns) {
        return new LeaderboardPageDeps(board, primaryTabs, tabs, statColumns, text, bucketKeys, menuTab,
                columns, allTabs, statsOnly, primaryLinks, primaryPictures, primaryHints, splits);
    }

    /**
     * A copy with no synthesized "All" tab on either axis, for axes whose buckets do not add up (a
     * section's columns, a week beside a month). Everything else is kept.
     */
    @Nonnull
    public LeaderboardPageDeps withoutAllTabs() {
        return new LeaderboardPageDeps(board, primaryTabs, tabs, statColumns, text, bucketKeys, menuTab,
                columnsByPrimary, false, statsOnly, primaryLinks, primaryPictures, primaryHints, splits);
    }

    /**
     * A copy that shows only the Stats view, with no Rankings view, sort row or Plays column: a board of
     * bare tallies, which never records a play or a best. Everything else is kept.
     */
    @Nonnull
    public LeaderboardPageDeps statsOnly() {
        return new LeaderboardPageDeps(board, primaryTabs, tabs, statColumns, text, bucketKeys, menuTab,
                columnsByPrimary, allTabs, true, primaryLinks, primaryPictures, primaryHints, splits);
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
                columnsByPrimary, allTabs, statsOnly, links, primaryPictures, primaryHints, splits);
    }

    /**
     * A copy whose primary tabs (and links) show pictures: {@code tab or link key -> item id}, each drawn as the
     * item's own icon on a row of view tabs ({@link #primaryTabsCarryColumns}) and above the empty state. Added
     * over the pictures already set; a key the map leaves out shows none. Everything else is kept.
     */
    @Nonnull
    public LeaderboardPageDeps withPrimaryPictures(@Nonnull Map<String, String> itemIds) {
        Map<String, String> pictures = new LinkedHashMap<>(primaryPictures);
        for (Map.Entry<String, String> e : itemIds.entrySet()) {
            if (e.getKey() != null && e.getValue() != null && !e.getValue().isBlank()) {
                pictures.put(e.getKey(), e.getValue().trim());
            }
        }
        return new LeaderboardPageDeps(board, primaryTabs, tabs, statColumns, text, bucketKeys, menuTab,
                columnsByPrimary, allTabs, statsOnly, primaryLinks, pictures, primaryHints, splits);
    }

    /**
     * A copy whose primary tabs each carry a line saying what counts there and how to get on the board
     * ({@code tab key -> line}), shown to a viewer with no place on it and under an empty board. The page paints
     * it on a {@code .TextSpans}, so it may carry parameters. Added over the lines already set; a tab the map
     * leaves out shows {@link LeaderboardScreenMessages#yourRankNone()} alone. Everything else is kept.
     */
    @Nonnull
    public LeaderboardPageDeps withPrimaryHints(@Nonnull Map<String, Message> hints) {
        Map<String, Message> lines = new LinkedHashMap<>(primaryHints);
        for (Map.Entry<String, Message> e : hints.entrySet()) {
            if (e.getKey() != null && e.getValue() != null) {
                lines.put(e.getKey(), e.getValue());
            }
        }
        return new LeaderboardPageDeps(board, primaryTabs, tabs, statColumns, text, bucketKeys, menuTab,
                columnsByPrimary, allTabs, statsOnly, primaryLinks, primaryPictures, lines, splits);
    }

    /**
     * A copy whose primary tabs may each split their board ({@code tab key -> split}): a row of choices under the
     * tab and a picker of single things, each with its own columns ({@link LeaderboardSplit}). Added over the
     * splits already set; a tab the map leaves out has none. Everything else is kept.
     */
    @Nonnull
    public LeaderboardPageDeps withSplits(@Nonnull Map<String, LeaderboardSplit> byPrimary) {
        Map<String, LeaderboardSplit> out = new LinkedHashMap<>(splits);
        for (Map.Entry<String, LeaderboardSplit> e : byPrimary.entrySet()) {
            if (e.getKey() != null && e.getValue() != null) {
                out.put(e.getKey(), e.getValue());
            }
        }
        return new LeaderboardPageDeps(board, primaryTabs, tabs, statColumns, text, bucketKeys, menuTab,
                columnsByPrimary, allTabs, statsOnly, primaryLinks, primaryPictures, primaryHints, out);
    }

    /** Primary tab {@code key}'s split of its board, or null when it has none ({@link #withSplits}). */
    @Nullable
    public LeaderboardSplit split(@Nonnull String key) {
        return splits.get(key);
    }

    /** The item {@code key}'s tab (or link) shows as its picture, or null for none ({@link #withPrimaryPictures}). */
    @Nullable
    public String primaryPicture(@Nonnull String key) {
        return primaryPictures.get(key);
    }

    /** The line saying how to get on {@code key}'s board, or null for none ({@link #withPrimaryHints}). */
    @Nullable
    public Message primaryHint(@Nonnull String key) {
        return primaryHints.get(key);
    }

    /**
     * Whether the primary tabs each bring their own columns ({@link #withColumnsByPrimary}), as a stat board's
     * sections and its boss fights do: the page then draws that row as view tabs, since each tab is a different
     * board, and any other row (a difficulty that filters the same columns) as segments.
     */
    public boolean primaryTabsCarryColumns() {
        return !columnsByPrimary.isEmpty();
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
