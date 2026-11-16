package com.ziggfreed.common.instance.dungeon.tier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import org.junit.jupiter.api.Test;

import com.ziggfreed.common.instance.dungeon.omen.OmenDraw;

/**
 * A tier opens on a clear of the dungeon its own {@code Opens} names, never on its rank ({@link TierLadder}): two rungs
 * of one rank opened by different dungeons each wait for their own clear, a tier that skips one opens on the clear it
 * names, and a party picks only the rungs open to every member. A tier's omens are its own for the whole week and nest
 * under the tier above's ({@link OmenDraw}). Untagged: nothing here builds an engine item.
 */
class TierLadderTest {

    private static final TierLadder.Rung T1 = new TierLadder.Rung("Deep_T1", 1, null, false);
    private static final TierLadder.Rung T2 = new TierLadder.Rung("Deep_T2", 2, "Deep_T1", false);
    private static final TierLadder.Rung T2_BOSS = new TierLadder.Rung("Deep_T2_Boss", 2, "Boss_X", false);
    private static final TierLadder.Rung T3 = new TierLadder.Rung("Deep_T3", 3, "Deep_T2", false);
    private static final TierLadder.Rung DAILY = new TierLadder.Rung("Deep_Daily", 1, null, true);

    private static Predicate<String> cleared(String... dungeons) {
        return Set.of(dungeons)::contains;
    }

    @Test
    void aTierOpensOnlyOnItsOwnOpensNeverOnItsRank() {
        List<TierLadder.Rung> ladder = List.of(T3, DAILY, T2_BOSS, T1, T2);
        assertEquals(List.of(T1, DAILY), TierLadder.pickable(ladder, List.of(cleared())),
                "a new player has tier 1 and the daily alone");
        assertEquals(List.of(T1, DAILY, T2), TierLadder.pickable(ladder, List.of(cleared("Deep_T1"))),
                "clearing tier 1 opens the rank-2 rung it opens, never the one Boss_X opens");
        assertTrue(TierLadder.pickable(ladder, List.of(cleared("Boss_X"))).contains(T2_BOSS),
                "the other rank-2 rung opens on its own clear");
        assertFalse(TierLadder.pickable(ladder, List.of(cleared("Boss_X"))).contains(T2));

        TierLadder.Rung lockedOne = new TierLadder.Rung("Side_T1", 1, "Boss_X", false);
        assertFalse(TierLadder.openToAll(lockedOne, List.of(cleared("Deep_T1"))),
                "a rank-1 rung with an Opens waits for it beside an open rank-1 rung");

        assertEquals(List.of(T1, DAILY), TierLadder.pickable(ladder, List.of(cleared("Deep_T1"), cleared())),
                "a party picks only what every member has open");
        assertEquals(T2, TierLadder.next(ladder, List.of(cleared())), "the next tier is the one tier 1 opens");
    }

    @Test
    void aTierThatSkipsOneOpensOnTheClearItNames() {
        TierLadder.Rung skip = new TierLadder.Rung("Deep_T3_Skip", 3, "Deep_T1", false);
        List<TierLadder.Rung> ladder = List.of(T1, T2, skip, T3);
        List<TierLadder.Rung> open = TierLadder.pickable(ladder, List.of(cleared("Deep_T1")));
        assertEquals(List.of(T1, T2, skip), open, "tier 1's clear opens both rungs that name it, not tier 3");
        assertEquals(T3, TierLadder.next(ladder, List.of(cleared("Deep_T1"))));
        assertNull(TierLadder.next(ladder, List.of(cleared("Deep_T1", "Deep_T2"))),
                "a party with every tier open has nothing next");
    }

    @Test
    void aTiersOmensHoldForTheWeekAndNestUnderTheTierAbove() {
        List<String> pool = List.of("Bolstering", "Blight", "Frenzy", "Gloom", "Sapping", "Tremors");
        long monday = OmenDraw.weekOf(Instant.parse("2026-11-16T00:00:00Z").toEpochMilli());
        long sunday = OmenDraw.weekOf(Instant.parse("2026-11-22T23:59:59Z").toEpochMilli());
        assertEquals(monday, sunday, "Monday 00:00 to Sunday night UTC is one week");

        List<String> tierTwo = OmenDraw.forTier(OmenDraw.weekSet(pool, 4, monday), 2);
        assertEquals(tierTwo, OmenDraw.forTier(OmenDraw.weekSet(pool, 4, sunday), 2),
                "two calls in one week give a tier the same omens");
        assertEquals(2, tierTwo.size());
        List<String> tierThree = OmenDraw.forTier(OmenDraw.weekSet(pool, 4, monday), 3);
        assertEquals(tierTwo, tierThree.subList(0, 2), "the tier above meets every omen of the tier below, and more");
        assertTrue(pool.containsAll(tierThree));
        assertEquals(List.of(), OmenDraw.forTier(OmenDraw.weekSet(pool, 4, monday), 0), "a Count of 0 meets none");
    }
}
