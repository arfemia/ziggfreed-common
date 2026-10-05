package com.ziggfreed.common.instance.leaderboard;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * How a two-axis selection names its bucket: the way every board always has
 * ({@code "<primary>_<secondary>"}), unless the board composes its own.
 */
class LeaderboardPageDepsTest {

    private static final LeaderboardScreenMessages TEXT = new EncounterLeaderboardMessages(null);

    @Test
    void aBoardBuiltTheOldWayComposesItsTwoAxesWithAnUnderscore() {
        LeaderboardPageDeps deps = new LeaderboardPageDeps(new Leaderboard("t"), List.of(), List.of(), List.of(), TEXT);
        assertEquals("nightmare_4", deps.bucketKey("nightmare", "4"));
        LeaderboardPageDeps single = new LeaderboardPageDeps(new Leaderboard("t"), List.of(), TEXT);
        assertEquals("a_b", single.bucketKey("a", "b"), "the single-axis constructor too");
    }

    @Test
    void aBoardGivenItsOwnComposerReadsItsOwnKeys() {
        LeaderboardPageDeps deps = new LeaderboardPageDeps(new Leaderboard("t"), List.of(), List.of(), List.of(), TEXT,
                (primary, secondary) -> "bosses:" + secondary + ":" + primary);
        assertEquals("bosses:4:hard", deps.bucketKey("hard", "4"));
    }
}
