package com.ziggfreed.common.instance.leaderboard;

import java.util.Comparator;
import java.util.Map;
import java.util.UUID;

import javax.annotation.Nonnull;

/**
 * How the {@link LeaderboardPage} Rankings view is sorted - the three metrics the user asked to
 * expose, with a sort toggle. Each carries the comparator over leaderboard entries. The page
 * highlights the active mode like the active tab; the consumer supplies the button labels via
 * {@link LeaderboardScreenMessages}.
 */
public enum SortMode {

    /** Highest best single-run score first (the default / legacy behavior). */
    BEST_SCORE,
    /** Highest cumulative total points first. */
    TOTAL_POINTS,
    /** Fastest winning time first; entries with no winning time sort last. */
    BEST_TIME;

    /** The comparator that ranks two entries best-first under this mode. */
    @Nonnull
    public Comparator<Map.Entry<UUID, LeaderboardEntry>> comparator() {
        return switch (this) {
            case BEST_SCORE -> Comparator.comparingLong(
                    (Map.Entry<UUID, LeaderboardEntry> e) -> e.getValue().bestScore).reversed();
            case TOTAL_POINTS -> Comparator.comparingLong(
                    (Map.Entry<UUID, LeaderboardEntry> e) -> e.getValue().totalPoints).reversed();
            case BEST_TIME -> Comparator.comparingLong(
                    (Map.Entry<UUID, LeaderboardEntry> e) -> timeKey(e.getValue().bestTimeSeconds));
        };
    }

    /** A winning time of 0 (no win) sorts after every real time. */
    private static long timeKey(int bestTimeSeconds) {
        return bestTimeSeconds > 0 ? bestTimeSeconds : Long.MAX_VALUE;
    }

    /** The metric value this mode ranks on (used for the "your rank" footer). */
    public long metric(@Nonnull LeaderboardEntry e) {
        return switch (this) {
            case BEST_SCORE -> e.bestScore;
            case TOTAL_POINTS -> e.totalPoints;
            case BEST_TIME -> e.bestTimeSeconds;
        };
    }

    /**
     * A comparator that ranks two entries highest-first by an arbitrary Stats-view metric: a
     * consumer stat key (e.g. {@code "stunned"}), or the reserved keys {@code "plays"} and
     * {@code "total"}. Unlike the three enum {@link #comparator() rank metrics}, this is the
     * sortable-by-column Stats view (every stat column header + the Plays header). An absent stat
     * defaults to 0, so unranked entries sort last and the order stays stable.
     */
    @Nonnull
    public static Comparator<Map.Entry<UUID, LeaderboardEntry>> byStat(@Nonnull String key) {
        return Comparator.comparingLong(
                (Map.Entry<UUID, LeaderboardEntry> e) -> statMetric(e.getValue(), key)).reversed();
    }

    /**
     * The Stats-view metric value for {@code key}: the {@code "plays"} count, the {@code "total"}
     * points, else the consumer stat counter (0 when absent). The single source the {@link #byStat}
     * comparator and the page's "your rank" footer both read, so sort order and the footer agree.
     */
    public static long statMetric(@Nonnull LeaderboardEntry e, @Nonnull String key) {
        if ("plays".equals(key)) {
            return e.plays;
        }
        if ("total".equals(key)) {
            return e.totalPoints;
        }
        return e.stat(key);
    }
}
