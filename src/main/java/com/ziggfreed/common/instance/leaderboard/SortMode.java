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
}
