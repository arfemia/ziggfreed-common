package com.ziggfreed.common.instance.leaderboard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/**
 * Engine-free unit tests for the generalized {@link Leaderboard}: total-points accrual, stat-delta
 * merge, cross-bucket aggregation ({@link Leaderboard#forBuckets}), and the {@link SortMode}
 * comparators. No data dir is set, so persistence is a no-op (pure in-memory logic).
 */
class LeaderboardTest {

    private static final UUID A = new UUID(0, 1);
    private static final UUID B = new UUID(0, 2);

    @Test
    void recordAccruesBestTotalPlaysAndStats() {
        Leaderboard lb = new Leaderboard("test");
        lb.record("nightmare_1", A, "Ada", 1000, 300, true, Map.of("stunned", 3L, "moonbloom", 10L));
        lb.record("nightmare_1", A, "Ada", 1500, 280, true, Map.of("stunned", 2L, "moonbloom", 5L));
        lb.record("nightmare_1", A, "Ada", 800, 0, false, Map.of("stunned", 1L));

        LeaderboardEntry e = lb.forBucket("nightmare_1").get(A);
        assertEquals(1500, e.bestScore, "best score keeps the max");
        assertEquals(3300L, e.totalPoints, "total points sum every record");
        assertEquals(3, e.plays, "plays count every record");
        assertEquals(280, e.bestTimeSeconds, "best time keeps the fastest WINNING time");
        assertEquals(6L, e.stat("stunned"), "stats merge across records");
        assertEquals(15L, e.stat("moonbloom"));
        assertEquals(0L, e.stat("shrines"), "absent stat defaults to 0");
    }

    @Test
    void lossDoesNotSetBestTime() {
        Leaderboard lb = new Leaderboard("test");
        lb.record("nightmare_1", A, "Ada", 500, 200, false, null);
        assertEquals(0, lb.forBucket("nightmare_1").get(A).bestTimeSeconds, "a loss never records a time");
    }

    @Test
    void forBucketsAggregatesGlobally() {
        Leaderboard lb = new Leaderboard("test");
        lb.record("nightmare_1", A, "Ada", 1000, 300, true, Map.of("stunned", 3L));
        lb.record("nightmare_2", A, "Ada", 1200, 250, true, Map.of("stunned", 4L));
        lb.record("hardcore_3", A, "Ada", 900, 0, false, Map.of("stunned", 1L));

        Map<UUID, LeaderboardEntry> global = lb.forBuckets(lb.bucketKeys());
        LeaderboardEntry e = global.get(A);
        assertEquals(1200, e.bestScore, "global best = max across buckets");
        assertEquals(3100L, e.totalPoints, "global total = sum across buckets");
        assertEquals(3, e.plays);
        assertEquals(250, e.bestTimeSeconds, "global best time = min winning across buckets");
        assertEquals(8L, e.stat("stunned"), "global stat = sum across buckets");
    }

    @Test
    void sortModesRankCorrectly() {
        Leaderboard lb = new Leaderboard("test");
        // A: lower best score, higher total, slower time. B: higher best score, lower total, faster time.
        lb.record("b", A, "Ada", 1000, 400, true, null);
        lb.record("b", A, "Ada", 1000, 400, true, null); // total 2000
        lb.record("b", B, "Ben", 1800, 200, true, null); // total 1800

        List<Map.Entry<UUID, LeaderboardEntry>> rows = new ArrayList<>(lb.forBucket("b").entrySet());

        rows.sort(SortMode.BEST_SCORE.comparator());
        assertEquals(B, rows.get(0).getKey(), "best score ranks B first");

        rows.sort(SortMode.TOTAL_POINTS.comparator());
        assertEquals(A, rows.get(0).getKey(), "total points ranks A first");

        rows.sort(SortMode.BEST_TIME.comparator());
        assertEquals(B, rows.get(0).getKey(), "best time ranks B (200s) first");
    }

    @Test
    void bestTimeSortsNoWinLast() {
        Leaderboard lb = new Leaderboard("test");
        lb.record("b", A, "Ada", 500, 0, false, null); // no winning time
        lb.record("b", B, "Ben", 500, 300, true, null);

        List<Map.Entry<UUID, LeaderboardEntry>> rows = new ArrayList<>(lb.forBucket("b").entrySet());
        rows.sort(SortMode.BEST_TIME.comparator());
        assertEquals(B, rows.get(0).getKey(), "a real winning time outranks no-win");
        assertTrue(rows.get(1).getKey().equals(A));
    }
}
