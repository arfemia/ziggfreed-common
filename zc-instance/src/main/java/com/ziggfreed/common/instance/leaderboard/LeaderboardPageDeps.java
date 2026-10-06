package com.ziggfreed.common.instance.leaderboard;

import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

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

    private final Leaderboard board;
    private final List<LeaderboardBucketTab> primaryTabs;
    private final List<LeaderboardBucketTab> tabs;
    private final List<StatColumnDef> statColumns;
    private final LeaderboardScreenMessages text;
    private final BucketKeys bucketKeys;
    @Nullable private final String menuTab;

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
        this(board, primaryTabs, tabs, statColumns, text, bucketKeys, null);
    }

    private LeaderboardPageDeps(@Nonnull Leaderboard board, @Nonnull List<LeaderboardBucketTab> primaryTabs,
                                @Nonnull List<LeaderboardBucketTab> tabs, @Nonnull List<StatColumnDef> statColumns,
                                @Nonnull LeaderboardScreenMessages text, @Nonnull BucketKeys bucketKeys,
                                @Nullable String menuTab) {
        this.board = board;
        this.primaryTabs = List.copyOf(primaryTabs);
        this.tabs = List.copyOf(tabs);
        this.statColumns = List.copyOf(statColumns);
        this.text = text;
        this.bucketKeys = bucketKeys;
        this.menuTab = menuTab;
    }

    /**
     * A copy that puts the page on the shared menu's rail with {@code tab} selected (a menu entry's id,
     * such as the Records tab's); null puts it back in its own frame. Everything else is kept.
     */
    @Nonnull
    public LeaderboardPageDeps withMenuTab(@Nullable String tab) {
        return new LeaderboardPageDeps(board, primaryTabs, tabs, statColumns, text, bucketKeys, tab);
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
