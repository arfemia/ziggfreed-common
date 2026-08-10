package com.ziggfreed.common.cast;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import org.junit.jupiter.api.Test;

/**
 * Real-time characterization of the {@link WorldFrameGate} once-per-millisecond CAS gate.
 * Uses generous margins and a fresh gate per iteration so timing is never tight.
 */
class WorldFrameGateTest {

    @Test
    void firstCallOfAMillisecondWinsAndTheSecondSameMsLoses() {
        // A fresh gate's first beginFrame always wins (prev stamp is 0). Retry until two
        // back-to-back calls land in the same millisecond, then assert first wins / second loses.
        for (int i = 0; i < 100_000; i++) {
            WorldFrameGate gate = new WorldFrameGate();
            long t0 = System.currentTimeMillis();
            boolean first = gate.beginFrame();
            boolean second = gate.beginFrame();
            long t1 = System.currentTimeMillis();
            if (t0 == t1) {
                assertTrue(first, "first call of a fresh gate in this ms wins");
                assertFalse(second, "second call in the same ms loses");
                return;
            }
        }
        fail("never caught two beginFrame calls within one millisecond");
    }

    @Test
    void aLaterMillisecondWinsAgain() {
        WorldFrameGate gate = new WorldFrameGate();
        assertTrue(gate.beginFrame(), "the first ever frame wins");
        sleep(5);
        assertTrue(gate.beginFrame(), "a frame a few ms later wins again");
        sleep(5);
        assertTrue(gate.beginFrame(), "and again after another gap");
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
