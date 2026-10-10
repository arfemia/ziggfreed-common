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
     *
     * <p>Copy on write once a board holds the entry: {@link Leaderboard} never changes a bag it has
     * published, it swaps in a changed copy, so a flush or a page reading the bag off the bucket's lock
     * never meets a write. Volatile, so the swap is seen at once; a reader takes the field once.
     */
    @Nullable public volatile CounterMap counters;

    /** The tally bag, created on first use: for an entry no board holds yet (a merged view, a fresh row). */
    @Nonnull
    public CounterMap counters() {
        CounterMap held = counters;
        if (held == null) {
            held = new CounterMap();
            counters = held;
        }
        return held;
    }

    /** The sum of every score recorded for this player in the bucket. */
    public long totalPoints() {
        CounterMap held = counters;
        return held == null ? 0L : held.get(TOTAL_POINTS);
    }

    /** One consumer tally, defaulting to 0 when the bag or the key is absent. */
    public long stat(@Nullable String key) {
        CounterMap held = counters;
        return held == null ? 0L : held.get(key);
    }
}
