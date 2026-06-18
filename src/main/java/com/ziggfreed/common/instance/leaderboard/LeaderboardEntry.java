package com.ziggfreed.common.instance.leaderboard;

/**
 * One player's best result in a {@link Leaderboard} bucket. Public mutable fields for
 * Gson (the persisted shape). Generalized verbatim from Kweebec's {@code Leaderboard.Entry}
 * so any instance can record a best score + best winning time + a play count + the
 * last-known display name.
 */
public final class LeaderboardEntry {

    public int bestScore;
    /** Best (lowest) WINNING completion time in seconds; 0 = no win recorded yet. */
    public int bestTimeSeconds;
    public int plays;
    public long lastUpdatedMs;
    /** Last-known display name of the player, captured at record time; null on legacy entries. */
    public String name;
}
