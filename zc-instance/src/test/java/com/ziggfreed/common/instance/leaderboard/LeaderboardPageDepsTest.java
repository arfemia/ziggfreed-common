package com.ziggfreed.common.instance.leaderboard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * How a two-axis selection names its bucket: the way every board always has
 * ({@code "<primary>_<secondary>"}), unless the board composes its own. And the rail knob: every
 * constructor a board was already built with leaves the page in its own frame.
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

    @Test
    void everyExistingConstructorLeavesThePageInItsOwnFrame() {
        Leaderboard board = new Leaderboard("t");
        assertNull(new LeaderboardPageDeps(board, List.of(), TEXT).menuTab());
        assertNull(new LeaderboardPageDeps(board, List.of(), List.of(), List.of(), TEXT).menuTab());
        assertNull(new LeaderboardPageDeps(board, List.of(), List.of(), List.of(), TEXT,
                LeaderboardPageDeps.UNDERSCORE).menuTab());
    }

    @Test
    void theRailKnobIsACopyThatKeepsEverythingElse() {
        LeaderboardPageDeps.BucketKeys keys = (primary, secondary) -> primary + ":" + secondary;
        LeaderboardPageDeps plain = new LeaderboardPageDeps(new Leaderboard("t"), List.of(), List.of(), List.of(), TEXT, keys);

        LeaderboardPageDeps onRail = plain.withMenuTab("records");

        assertEquals("records", onRail.menuTab());
        assertNull(plain.menuTab(), "the original is untouched");
        assertSame(plain.board(), onRail.board());
        assertSame(plain.text(), onRail.text());
        assertEquals("a:b", onRail.bucketKey("a", "b"), "the board's own composition rides along");
        assertNull(onRail.withMenuTab(null).menuTab(), "null puts it back in its own frame");
    }
}
