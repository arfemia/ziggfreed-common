package com.ziggfreed.common.track.boost;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * How the boosts on one track add up under the server's cap, and whether one more start fits: every lift adds,
 * a server boost and a personal one alike, and the sum never passes the cap; HighestOnly keeps the strongest of
 * each scope; the wait is asked before the boost's own most at once, and that before the owner's limit.
 */
class BoostStackTest {

    /** A cap for the arithmetic; the shipped default is a setting, not something this test pins. */
    private static final double CAP = 200.0;

    private static BoostStack.Lift personal(double percent) {
        return new BoostStack.Lift(percent, BoostScope.PERSONAL);
    }

    private static BoostStack.Lift server(double percent) {
        return new BoostStack.Lift(percent, BoostScope.SERVER);
    }

    @Test
    void everyLiftAddsAndTheSumStopsAtTheCap() {
        assertEquals(0.0, BoostStack.percent(List.of(), CAP, false), "nothing running lifts nothing");
        assertEquals(75.0, BoostStack.percent(List.of(personal(50), personal(25)), CAP, false),
                "two personal boosts add");
        assertEquals(150.0, BoostStack.percent(List.of(server(100), personal(50)), CAP, false),
                "a server boost and a personal one add");
        assertEquals(200.0, BoostStack.percent(List.of(server(100), personal(150)), CAP, false),
                "a server boost and a personal one stack only up to the cap");
        assertEquals(300.0, BoostStack.percent(List.of(server(100), personal(150), personal(100)), 300.0, false),
                "an owner's cap of 300 lets them reach it");
        assertEquals(0.0, BoostStack.percent(List.of(server(100)), 0.0, false), "a cap of 0 turns every boost off");
        assertEquals(-20.0, BoostStack.percent(List.of(personal(30), personal(-50)), CAP, false),
                "the cap bounds only how high the sum goes, so a negative boost still takes away");
    }

    @Test
    void highestOnlyKeepsTheStrongestOfEachScopeThenCaps() {
        assertEquals(150.0, BoostStack.percent(List.of(server(100), server(50), personal(50), personal(25)), CAP,
                true), "the strongest server boost plus the strongest personal one");
        assertEquals(200.0, BoostStack.percent(List.of(server(150), personal(150)), CAP, true),
                "and the two together still stop at the cap");
        assertEquals(50.0, BoostStack.percent(List.of(server(-30), personal(50)), CAP, true),
                "a scope whose strongest lift is below nothing counts as nothing");
    }

    @Test
    void theSumIsAMultiplierThatNeverGoesBelowNothing() {
        assertEquals(1.0, BoostStack.multiplier(0.0));
        assertEquals(1.5, BoostStack.multiplier(50.0));
        assertEquals(3.0, BoostStack.multiplier(BoostStack.percent(List.of(server(400)), CAP, false)),
                "a capped sum is the multiplier at the cap");
        assertEquals(0.0, BoostStack.multiplier(-150.0));
    }

    @Test
    void aStartFitsOnlyPastTheWaitUnderTheBoostsOwnMostAndTheOwnersLimit() {
        long now = 1_000_000L;
        assertEquals(BoostOutcome.STARTED, BoostStack.fits(now, 0L, 0, 0, 0, 3, BoostScope.PERSONAL));
        assertEquals(BoostOutcome.COOLDOWN, BoostStack.fits(now, now + 1L, 0, 0, 0, 3, BoostScope.PERSONAL),
                "the wait is asked first");
        assertEquals(BoostOutcome.STARTED, BoostStack.fits(now, now, 0, 0, 0, 3, BoostScope.PERSONAL),
                "a wait ending now is over");
        assertEquals(BoostOutcome.STACK_FULL, BoostStack.fits(now, 0L, 2, 2, 2, 3, BoostScope.PERSONAL),
                "the boost's own Stack.Max before the owner's limit");
        assertEquals(BoostOutcome.STARTED, BoostStack.fits(now, 0L, 5, 0, 2, 3, BoostScope.PERSONAL),
                "a Max of 0 sets no limit of its own");
        assertEquals(BoostOutcome.PERSONAL_FULL, BoostStack.fits(now, 0L, 0, 0, 3, 3, BoostScope.PERSONAL));
        assertEquals(BoostOutcome.SERVER_FULL, BoostStack.fits(now, 0L, 0, 0, 3, 3, BoostScope.SERVER));
        assertEquals(BoostOutcome.PERSONAL_FULL, BoostStack.fits(now, 0L, 0, 0, 0, 0, BoostScope.PERSONAL),
                "a limit of 0 lets nobody start one");
    }
}
