package com.ziggfreed.common.instance.leaderboard;

import java.util.List;

import javax.annotation.Nonnull;

/**
 * The immutable consumer-policy bundle a {@link LeaderboardPage} is built from: the
 * {@link Leaderboard} to read, the ordered {@link LeaderboardBucketTab} list (the tabs),
 * and the locale-free {@link LeaderboardScreenMessages} chrome. Built once at the
 * consumer's startup; the page stays mod-agnostic.
 */
public final class LeaderboardPageDeps {

    private final Leaderboard board;
    private final List<LeaderboardBucketTab> tabs;
    private final LeaderboardScreenMessages text;

    public LeaderboardPageDeps(@Nonnull Leaderboard board, @Nonnull List<LeaderboardBucketTab> tabs,
                               @Nonnull LeaderboardScreenMessages text) {
        this.board = board;
        this.tabs = List.copyOf(tabs);
        this.text = text;
    }

    @Nonnull
    public Leaderboard board() {
        return board;
    }

    @Nonnull
    public List<LeaderboardBucketTab> tabs() {
        return tabs;
    }

    @Nonnull
    public LeaderboardScreenMessages text() {
        return text;
    }
}
