package com.ziggfreed.common.reputation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.ziggfreed.common.reputation.ReputationLadder.Progress;
import com.ziggfreed.common.reputation.ReputationLadder.Rank;

/**
 * The ladder maths: a value's rank is the highest rank whose floor it reaches (a value below the bottom
 * reads the bottom, the top is open), the ranks a credit covers (from the starting rank to the current one,
 * or below the start only the ranks reached going down), where a value stands inside its rank, and the
 * payout counting past the top.
 */
class ReputationLadderTest {

    private static final ReputationLadder LADDER = ReputationLadder.of(ReputationFixtures.LADDER);

    private static List<String> ids(List<Rank> ranks) {
        return ranks.stream().map(Rank::id).toList();
    }

    @Test
    void aValueReadsTheHighestRankWhoseFloorItReaches() {
        assertEquals("Neutral", LADDER.rankFor(0).id());
        assertEquals("Neutral", LADDER.rankFor(999).id());
        assertEquals("Friendly", LADDER.rankFor(1_000).id(), "MinValue is included");
        assertEquals("Exalted", LADDER.rankFor(5_000_000_000L).id(), "the top is open");
        assertEquals("Hated", LADDER.rankFor(-2_000_000L).id(), "below the bottom still reads the bottom rank");
    }

    @Test
    void theLadderIsSortedWhateverOrderItArrivesIn() {
        List<Rank> shuffled = new ArrayList<>(ReputationFixtures.LADDER);
        Collections.reverse(shuffled);
        assertEquals(ids(ReputationFixtures.LADDER), ids(ReputationLadder.of(shuffled).ranks()));
    }

    @Test
    void aLadderOfFewerThanTwoRanksAnswersNoRank() {
        ReputationLadder one = ReputationLadder.of(List.of(new Rank("Only", -10, 10)));
        assertFalse(one.usable(), "the engine's own clamp is garbage below two ranks");
        assertNull(one.rankFor(0));
        assertTrue(one.credited(one.rankFor(0), one.rankFor(0)).isEmpty());
        assertTrue(ReputationLadder.EMPTY.ranks().isEmpty());
    }

    @Test
    void aCreditRunsFromTheStartingRankToTheCurrentOne() {
        Rank neutral = LADDER.byId("Neutral");
        assertEquals(List.of("Neutral", "Friendly"), ids(LADDER.credited(LADDER.byId("Friendly"), neutral)),
                "at or above the start: the start up to the current rank, never a rank below the start");
        assertEquals(List.of("Neutral"), ids(LADDER.credited(neutral, neutral)));
        assertEquals(List.of("Unfriendly"), ids(LADDER.credited(LADDER.byId("Unfriendly"), neutral)),
                "below the start: only the ranks reached going down, never the start's own");
        assertEquals(List.of("Hated", "Unfriendly"), ids(LADDER.credited(LADDER.byId("Hated"), neutral)));
        assertEquals(List.of("Hated", "Unfriendly", "Neutral"), ids(LADDER.credited(neutral, null)),
                "a start off the ladder counts from the bottom");
        assertTrue(LADDER.credited(null, neutral).isEmpty());
        assertEquals(3, LADDER.indexOf(LADDER.byId("friendly")));
        assertEquals(-1, LADDER.indexOf(null));
    }

    @Test
    void aRankIsFoundByIdWithoutRegardToCaseAndKnowsItsNeighbours() {
        assertEquals("Revered", LADDER.byId(" revered ").id());
        assertNull(LADDER.byId("Champion"));
        assertEquals("Honored", LADDER.next(LADDER.byId("Friendly")).id());
        assertNull(LADDER.next(LADDER.byId("Exalted")), "nothing above the top");
        assertEquals("Exalted", LADDER.top().id());
        assertEquals("Hated", LADDER.bottom().id());
    }

    @Test
    void progressRunsFromTheRankFloorToTheNextAndPastTheTopAgainstEvery() {
        assertEquals(new Progress(500, 2_000), LADDER.progress(1_500, 0));
        assertEquals(new Progress(1_000, 5_000), LADDER.progress(22_000, 5_000));
        assertEquals(Progress.FULL, LADDER.progress(22_000, 0), "the open top with no Every reads full");
        assertEquals(Progress.FULL, ReputationLadder.EMPTY.progress(10, 0));
    }

    @Test
    void crossingsCountEachNewMultipleOfEveryPastTheFloor() {
        assertEquals(3, ReputationLadder.crossings(25_000, 36_000, 21_000, 5_000));
        assertEquals(1, ReputationLadder.crossings(20_000, 26_000, 21_000, 5_000), "the floor itself pays nothing");
        assertEquals(0, ReputationLadder.crossings(26_000, 30_999, 21_000, 5_000));
        assertEquals(0, ReputationLadder.crossings(36_000, 25_000, 21_000, 5_000), "a fall pays nothing");
        assertEquals(0, ReputationLadder.crossings(25_000, 36_000, 21_000, 0), "no Every, no payout");
    }

    @Test
    void theNextRewardSitsOneEveryPastTheLastOneCrossed() {
        assertEquals(26_000, ReputationLadder.nextRewardAt(25_000, 21_000, 5_000));
        assertEquals(26_000, ReputationLadder.nextRewardAt(10_000, 21_000, 5_000));
        assertEquals(31_000, ReputationLadder.nextRewardAt(26_000, 21_000, 5_000));
    }
}
