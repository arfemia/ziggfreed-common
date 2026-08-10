package com.ziggfreed.common.instance.leaderboard;

import java.util.Map;

/**
 * One player's best result in a {@link Leaderboard} bucket. Public mutable fields for
 * Gson (the persisted shape). Generalized verbatim from Kweebec's {@code Leaderboard.Entry}
 * so any instance can record a best score + best winning time + a play count + the
 * last-known display name, PLUS a cumulative total-points tally and a generic, consumer-keyed
 * stat counter map (so the primitive stays mod-agnostic: the consumer chooses the stat keys).
 */
public final class LeaderboardEntry {

    public int bestScore;
    /** Best (lowest) WINNING completion time in seconds; 0 = no win recorded yet. */
    public int bestTimeSeconds;
    public int plays;
    public long lastUpdatedMs;
    /** Last-known display name of the player, captured at record time; null on legacy entries. */
    public String name;
    /** Cumulative sum of every score recorded for this player in the bucket (a lifetime tally). */
    public long totalPoints;
    /**
     * Cumulative per-stat counters, keyed by consumer-chosen strings (Kweebec: "stunned" /
     * "moonbloom" / "shrines"). Lazily allocated; null on legacy entries (treat as empty).
     */
    public Map<String, Long> stats;

    /** A stat counter, defaulting to 0 when the map or key is absent. */
    public long stat(String key) {
        if (stats == null) {
            return 0L;
        }
        Long v = stats.get(key);
        return v == null ? 0L : v;
    }
}
