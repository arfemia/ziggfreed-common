package com.ziggfreed.common.instance.zone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Deterministic unit tests for the PURE {@link ZoneHoldTimer} continuous-hold-with-reset state machine. */
class ZoneHoldTimerTest {

    @Test
    void completesAfterContinuousHold() {
        ZoneHoldTimer t = new ZoneHoldTimer(5.0);
        assertFalse(t.update(3, 3, 0L), "not complete at t=0");
        assertFalse(t.update(3, 3, 4000L), "not complete before the duration");
        assertTrue(t.update(3, 3, 5000L), "complete exactly at the duration");
        assertTrue(t.isComplete());
    }

    @Test
    void resetsWhenGroupBreaks() {
        ZoneHoldTimer t = new ZoneHoldTimer(5.0);
        assertFalse(t.update(3, 3, 0L));
        assertTrue(t.isHolding());
        // One occupant steps off -> the hold breaks and resets.
        assertFalse(t.update(2, 3, 3000L));
        assertFalse(t.isHolding(), "hold resets when occupants drop below required");
        // Reassembling restarts the clock from zero, so 3000ms later is NOT yet complete.
        assertFalse(t.update(3, 3, 6000L));
        assertFalse(t.update(3, 3, 10000L), "5s from the RESTART (6000), not from the original start");
        assertTrue(t.update(3, 3, 11000L), "complete 5s after the restart");
    }

    @Test
    void neverCompletesWithoutEnoughOccupants() {
        ZoneHoldTimer t = new ZoneHoldTimer(2.0);
        assertFalse(t.update(2, 3, 0L));
        assertFalse(t.update(2, 3, 10000L));
        assertFalse(t.isHolding());
    }

    @Test
    void requiredZeroNeverCompletes() {
        ZoneHoldTimer t = new ZoneHoldTimer(0.0);
        assertFalse(t.update(0, 0, 0L), "required <= 0 can never complete");
        assertFalse(t.update(5, 0, 1000L));
    }

    @Test
    void zeroHoldCompletesInstantlyWhenAssembled() {
        ZoneHoldTimer t = new ZoneHoldTimer(0.0);
        assertTrue(t.update(2, 2, 0L), "a 0s hold completes the instant the group is present");
    }

    @Test
    void remainingSecondsCountsDownAndLatchesZero() {
        ZoneHoldTimer t = new ZoneHoldTimer(5.0);
        assertEquals(5, t.remainingSeconds(0L), "full hold reported while idle");
        t.update(2, 2, 1000L);
        assertEquals(4, t.remainingSeconds(2000L));
        t.update(2, 2, 6000L);
        assertEquals(0, t.remainingSeconds(6000L), "0 once complete");
    }

    @Test
    void completionLatchesEvenIfOccupancyLaterDrops() {
        ZoneHoldTimer t = new ZoneHoldTimer(2.0);
        assertFalse(t.update(2, 2, 0L));
        assertTrue(t.update(2, 2, 2000L));
        // Once complete it stays complete regardless of later updates.
        assertTrue(t.update(0, 2, 3000L));
        assertTrue(t.isComplete());
    }
}
