package com.ziggfreed.common.ui.hud.panel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * When a row goes away. A row runs its linger from its last move; on a panel that holds while a page
 * is open, a row moved under a page, or still up when one opens, stops its clock, because the client
 * draws no HUD over a page and a row whose time ran out there was never seen. When no page is open
 * again it runs its whole linger from that moment and counts as just moved. What waits is held to a
 * cap, the oldest going first, so a long stay in a page never piles rows up.
 */
class HudRowClockTest {

    private static final long LINGER = 5_000L;

    @Test
    void aRowMovedWithNoPageOpenRunsItsLingerFromTheMove() {
        HudRowClock clock = HudRowClock.moved(1_000L, LINGER, false);

        assertFalse(clock.waiting());
        assertEquals(1_000L, clock.lastMovedMs());
        assertEquals(6_000L, clock.expiresAtMs());
        assertFalse(clock.expired(5_999L));
        assertTrue(clock.expired(6_000L));
    }

    @Test
    void aRowMovedWhileAPageIsOpenWaitsWithItsClockStopped() {
        HudRowClock clock = HudRowClock.moved(1_000L, LINGER, true);

        assertTrue(clock.waiting());
        assertEquals(1_000L, clock.lastMovedMs());
        assertFalse(clock.expired(1_000L + 3_600_000L), "however long the page stays open, the row is not lost");
        assertFalse(clock.expired(Long.MAX_VALUE - 1));
    }

    @Test
    void closingThePageStartsTheWholeLingerFromTheCloseAndCountsAsAMove() {
        HudRowClock released = HudRowClock.moved(1_000L, LINGER, true).pageClosed(60_000L, LINGER);

        assertFalse(released.waiting());
        assertEquals(65_000L, released.expiresAtMs(), "the whole linger, from the moment it can be seen");
        assertEquals(60_000L, released.lastMovedMs(), "so it pulses, and ranks as the newest move for a slot");
        assertFalse(released.expired(64_999L));
        assertTrue(released.expired(65_000L));
    }

    @Test
    void aPageOpeningStopsTheClockOfARowStillUpAndLeavesOneWhoseTimeRanOut() {
        HudRowClock running = HudRowClock.moved(1_000L, LINGER, false);

        HudRowClock covered = running.pageOpened(3_000L);
        assertTrue(covered.waiting(), "it was up when the page covered it, so it comes back after");
        assertEquals(14_000L, covered.pageClosed(9_000L, LINGER).expiresAtMs(), "with its whole linger again");

        HudRowClock spent = running.pageOpened(6_000L);
        assertFalse(spent.waiting(), "a row whose time had already run is not brought back");
        assertTrue(spent.expired(6_000L));
    }

    @Test
    void noPageOpenLeavesARunningRowAsItIs() {
        HudRowClock running = HudRowClock.moved(1_000L, LINGER, false);

        assertEquals(running, running.pageClosed(2_000L, LINGER));
    }

    @Test
    void aHeldRowNeverRunsOutOnAClockAndALongLingerNeverWrapsIntoThePast() {
        assertEquals(HudRowClock.NEVER, HudRowClock.moved(1_000L, HudRowLook.LINGER_HELD, false).expiresAtMs());
        HudRowClock heldThroughAPage = HudRowClock.moved(1_000L, HudRowLook.LINGER_HELD, true)
                .pageClosed(2_000L, HudRowLook.LINGER_HELD);
        assertFalse(heldThroughAPage.waiting());
        assertEquals(HudRowClock.NEVER, heldThroughAPage.expiresAtMs());
        assertEquals(HudRowClock.NEVER, HudRowClock.expiryFrom(Long.MAX_VALUE - 10L, LINGER),
                "past the end of time is never, not a moment long gone");
    }

    @Test
    void sendingEveryRowAwayLeavesAWaitingRowWaiting() {
        HudRowClock running = HudRowClock.moved(1_000L, LINGER, false);
        assertEquals(3_000L, running.fadeBy(3_000L).expiresAtMs());
        assertEquals(6_000L, running.fadeBy(9_000L).expiresAtMs(), "a row going sooner keeps its own time");

        HudRowClock waiting = HudRowClock.moved(1_000L, LINGER, true);
        assertTrue(waiting.fadeBy(3_000L).waiting(), "its clock has not started, so there is nothing to bring forward");
        assertFalse(waiting.fadeBy(3_000L).expired(10_000L));
    }

    @Test
    void pastTheCapTheOldestWaitingRowsGoFirst() {
        Map<String, Long> waiting = new LinkedHashMap<>();
        waiting.put("a", 100L);
        waiting.put("b", 400L);
        waiting.put("c", 300L);
        waiting.put("d", 200L);

        assertEquals(Set.of("a", "d"), Set.copyOf(HudRowClock.beyondCap(waiting, 2)),
                "the two most recent wait on; the two oldest go");
        assertTrue(HudRowClock.beyondCap(waiting, 4).isEmpty(), "at the cap nothing goes");
        assertTrue(HudRowClock.beyondCap(waiting, 9).isEmpty());
        assertEquals(List.of("y"), HudRowClock.beyondCap(Map.of("x", 5L, "y", 5L), 1),
                "a tie on recency keeps the lower id");
        assertEquals(3, HudRowClock.beyondCap(waiting, 0).size(), "a cap below one still keeps the newest row");
    }
}
