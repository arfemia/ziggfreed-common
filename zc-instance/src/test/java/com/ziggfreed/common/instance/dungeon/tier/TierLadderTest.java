package com.ziggfreed.common.instance.dungeon.tier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * A tier opens on a clear of the tier below ({@link TierLadder}): a player's opened rank reads which rungs' {@code Opens}
 * they have cleared, a party picks up to its lowest member's rank, and the next rung names what to clear. Untagged:
 * nothing here builds an engine item.
 */
class TierLadderTest {

    private static final TierLadder.Rung T1 = new TierLadder.Rung("Deep_T1", 1, null, false);
    private static final TierLadder.Rung T2 = new TierLadder.Rung("Deep_T2", 2, "Deep_T1", false);
    private static final TierLadder.Rung T3 = new TierLadder.Rung("Deep_T3", 3, "Deep_T2", false);
    private static final TierLadder.Rung DAILY = new TierLadder.Rung("Deep_Daily", 1, null, true);
    private static final List<TierLadder.Rung> LADDER = List.of(T3, DAILY, T1, T2);

    @Test
    void tierOpeningReadsTheRankBelow() {
        assertEquals(1, TierLadder.openedRank(LADDER, Set.<String>of()::contains), "a new player has tier 1 alone");
        assertEquals(2, TierLadder.openedRank(LADDER, Set.of("Deep_T1")::contains), "a clear of tier 1 opens tier 2");
        assertEquals(3, TierLadder.openedRank(LADDER, Set.of("Deep_T1", "Deep_T2")::contains),
                "a clear of tier 2 opens tier 3");

        int party = TierLadder.partyRank(List.of(3, 1, 2));
        assertEquals(1, party, "a party picks up to its lowest member's rank");
        assertEquals(List.of(T1, DAILY), TierLadder.pickable(LADDER, party), "the normal rung before the daily");
        assertEquals(T2, TierLadder.next(LADDER, party), "the next tier names the clear that opens it");
        assertNull(TierLadder.next(LADDER, 3), "a party with every tier open has nothing next");
    }
}
