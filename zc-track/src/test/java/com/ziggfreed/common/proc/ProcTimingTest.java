package com.ziggfreed.common.proc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The timing a proc's wait and a counter's decay rest on, pure: a wait cut by its stat and held to its ceiling, a
 * wait that opens again exactly when it ends and is kept per proc and per target, and stacks that hold until their
 * decay starts, then fall one step at a time. No balance number is pinned: every figure here is the test's own.
 */
class ProcTimingTest {

    private static final long SECOND = 1_000_000_000L;

    @AfterEach
    void clear() {
        ProcCooldowns.clear();
    }

    @Test
    void aWaitIsCutByItsStat_heldToTheCeiling_andNoWaitIsNoWait() {
        assertEquals(10 * SECOND, ProcCooldowns.readyAt(0L, 10.0, 0.0, 50.0));
        assertEquals(8 * SECOND, ProcCooldowns.readyAt(0L, 10.0, 20.0, 50.0), "a 20 point cut takes a fifth off");
        assertEquals(5 * SECOND, ProcCooldowns.readyAt(0L, 10.0, 90.0, 50.0), "the cut never passes its ceiling");
        assertEquals(10 * SECOND, ProcCooldowns.readyAt(0L, 10.0, -30.0, 50.0), "a negative cut adds nothing");
        assertEquals(0L, ProcCooldowns.readyAt(0L, 10.0, 100.0, 200.0), "a ceiling past 100 still stops at no wait");
        assertEquals(7L, ProcCooldowns.readyAt(7L, 0.0, 0.0, 50.0));
        assertEquals(7L, ProcCooldowns.readyAt(7L, -3.0, 0.0, 50.0));
        assertEquals(7L, ProcCooldowns.readyAt(7L, Double.NaN, 0.0, 50.0));
    }

    @Test
    void aWaitOpensWhenItEnds_perProcAndPerTarget() {
        UUID holder = UUID.randomUUID();
        assertTrue(ProcCooldowns.ready(holder, "cleanse", null, 0L), "nothing stamped: ready");
        ProcCooldowns.stamp(holder, "cleanse", null, 0L, 10.0, 0.0, 50.0);
        assertFalse(ProcCooldowns.ready(holder, "cleanse", null, 10 * SECOND - 1));
        assertTrue(ProcCooldowns.ready(holder, "cleanse", null, 10 * SECOND));
        assertTrue(ProcCooldowns.ready(holder, "other", null, 1L), "another proc keeps its own wait");
        assertTrue(ProcCooldowns.ready(UUID.randomUUID(), "cleanse", null, 1L), "another player keeps their own");
        ProcCooldowns.stamp(holder, "brace", null, 0L, 0.0, 0.0, 50.0);
        assertTrue(ProcCooldowns.ready(holder, "brace", null, 0L), "a wait of nothing is never kept");
    }

    @Test
    void stacksHoldUntilTheirDecayStarts_thenFallAStepAtATime() {
        assertEquals(4.0, Counters.decayed(4.0, 8 * SECOND - 1, 8.0, 1.0, 1.0));
        assertEquals(3.0, Counters.decayed(4.0, 8 * SECOND, 8.0, 1.0, 1.0), "the first fall comes when decay starts");
        assertEquals(2.0, Counters.decayed(4.0, 9 * SECOND, 8.0, 1.0, 1.0));
        assertEquals(1.0, Counters.decayed(4.0, 10 * SECOND + SECOND / 2, 8.0, 1.0, 1.0));
        assertEquals(0.0, Counters.decayed(4.0, 60 * SECOND, 8.0, 1.0, 1.0), "never below nothing");
        assertEquals(0.0, Counters.decayed(4.0, 8 * SECOND, 8.0, 0.0, 1.0), "no step: every stack at once");
        assertEquals(4.0, Counters.decayed(4.0, 60 * SECOND, 8.0, 1.0, 0.0), "a fall of nothing takes nothing");
        assertEquals(0.0, Counters.decayed(0.0, 0L, 8.0, 1.0, 1.0));
        assertEquals(1.5, Counters.decayed(3.5, 0L, 0.0, 2.0, 2.0), "fractional stacks fall the same way");
    }
}
