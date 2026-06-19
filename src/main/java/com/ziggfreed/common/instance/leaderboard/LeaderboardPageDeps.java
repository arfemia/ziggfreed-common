package com.ziggfreed.common.instance.leaderboard;

import java.util.List;

import javax.annotation.Nonnull;

/**
 * The immutable consumer-policy bundle a {@link LeaderboardPage} is built from: the
 * {@link Leaderboard} to read (one board per game-mode, selected by the consumer), the
 * optional PRIMARY tab axis ({@code primaryTabs} - e.g. difficulty), the SECONDARY tab axis
 * ({@code tabs} - e.g. party size), the Stats-view {@link StatColumnDef stat columns}, and the
 * locale-free {@link LeaderboardScreenMessages} chrome. Built once at the consumer's startup; the
 * page stays mod-agnostic.
 *
 * <p>The active bucket key is {@code primary + "_" + secondary} when a primary axis exists, else
 * just the secondary key (back-compat with a single-axis board). When {@code primaryTabs} /
 * {@code statColumns} are empty the page renders exactly the original single-axis, score-only board.
 */
public final class LeaderboardPageDeps {

    private final Leaderboard board;
    private final List<LeaderboardBucketTab> primaryTabs;
    private final List<LeaderboardBucketTab> tabs;
    private final List<StatColumnDef> statColumns;
    private final LeaderboardScreenMessages text;

    /** Back-compat: a single-axis, score-only board (no difficulty axis, no stats view). */
    public LeaderboardPageDeps(@Nonnull Leaderboard board, @Nonnull List<LeaderboardBucketTab> tabs,
                               @Nonnull LeaderboardScreenMessages text) {
        this(board, List.of(), tabs, List.of(), text);
    }

    public LeaderboardPageDeps(@Nonnull Leaderboard board, @Nonnull List<LeaderboardBucketTab> primaryTabs,
                               @Nonnull List<LeaderboardBucketTab> tabs, @Nonnull List<StatColumnDef> statColumns,
                               @Nonnull LeaderboardScreenMessages text) {
        this.board = board;
        this.primaryTabs = List.copyOf(primaryTabs);
        this.tabs = List.copyOf(tabs);
        this.statColumns = List.copyOf(statColumns);
        this.text = text;
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
}
