package com.ziggfreed.common.instance.leaderboard;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.counter.CounterMap;

/**
 * One player's best result in a {@link Leaderboard} bucket: the BESTS and the play count as their
 * own fields, plus every CUMULATIVE tally in one {@link CounterMap}.
 *
 * <p><b>Why the split.</b> A best score and a best time are one-value-per-entry facts with their own
 * comparison rule; a lifetime tally is arbitrary, consumer-named, and always summed. Keeping the
 * tallies in a counter bag means the record path, the cross-bucket aggregate, and any consumer
 * reading them all share ONE summing authority instead of three hand-written merges - and a consumer
 * adds a new tally by naming it, with no field and no format change.
 *
 * <p>Total points is the one tally this library names itself, under the reserved key
 * {@value #TOTAL_POINTS}: a consumer stat key must not use it.
 *
 * <p>Public mutable fields for the JSON persister (this IS the persisted shape).
 */
public final class LeaderboardEntry {

    /** Reserved counter key: the sum of every score ever recorded for this player in the bucket. */
    public static final String TOTAL_POINTS = "total_points";

    public int bestScore;
    /** Best (lowest) WINNING completion time in seconds; 0 = no win recorded yet. */
    public int bestTimeSeconds;
    public int plays;
    public long lastUpdatedMs;
    /** Last-known display name of the player, captured at record time; null until one is recorded. */
    public String name;
    /**
     * Every cumulative tally: {@value #TOTAL_POINTS} plus whatever the consumer names (a hit count,
     * a pickup count). Lazily allocated, so an entry that has counted nothing carries no map.
     */
    @Nullable public CounterMap counters;

    /** The tally bag, created on first use. */
    @Nonnull
    public CounterMap counters() {
        if (counters == null) {
            counters = new CounterMap();
        }
        return counters;
    }

    /** The sum of every score recorded for this player in the bucket. */
    public long totalPoints() {
        return counters == null ? 0L : counters.get(TOTAL_POINTS);
    }

    /** One consumer tally, defaulting to 0 when the bag or the key is absent. */
    public long stat(@Nullable String key) {
        return counters == null ? 0L : counters.get(key);
    }
}
