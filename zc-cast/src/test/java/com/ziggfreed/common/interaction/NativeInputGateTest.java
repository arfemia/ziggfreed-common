package com.ziggfreed.common.interaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The slice of {@link NativeInputGate} testable without a live Hytale server: the {@link
 * NativeInputGate.Verdict} decision, plus the argument guards that return before the first engine
 * touch. Reading a real root's condition prefix needs the interaction asset stores and live
 * components, so that half is smoke-tested in the consuming mods.
 *
 * <p>The verdict cases are the load-bearing ones. Which of the two "no"s a caller got decides
 * whether a key press belongs to the held item or to whatever else wants it, and the pair that
 * looks redundant - unsatisfied with a resource gate, versus satisfied without one - is exactly the
 * distinction between "the bar is empty right now" and "this chain has no bar at all".
 */
class NativeInputGateTest {

    @Test
    void readyToSpend_needsBothAResourceGateAndASatisfiedOne() {
        assertTrue(new NativeInputGate.Verdict(true, true).readyToSpend(),
                "a chain that spends something and can pay is the one ready state");
    }

    @Test
    void readyToSpend_isFalseWhenTheResourceGateFails() {
        assertFalse(new NativeInputGate.Verdict(true, false).readyToSpend(),
                "an empty bar means the item has nothing banked up right now");
    }

    @Test
    void readyToSpend_isFalseWithoutAResourceGateEvenWhenEverythingPasses() {
        assertFalse(new NativeInputGate.Verdict(false, true).readyToSpend(),
                "a chain gated only on durability, or on nothing, is as ready on the thousandth "
                        + "press as the first, so it never means a move is waiting");
    }

    @Test
    void none_claimsNothing() {
        assertFalse(NativeInputGate.Verdict.NONE.hasResourceGate());
        assertFalse(NativeInputGate.Verdict.NONE.satisfied());
        assertFalse(NativeInputGate.Verdict.NONE.readyToSpend());
    }

    @Test
    void probe_nullStoreAndRef_answersNone() {
        assertEquals(NativeInputGate.Verdict.NONE,
                NativeInputGate.probe(null, null, null, null, "Some_Root"));
    }

    @Test
    void probe_blankRootId_answersNone() {
        assertEquals(NativeInputGate.Verdict.NONE, NativeInputGate.probe(null, null, null, null, ""));
    }

    @Test
    void probe_nullRootId_answersNone() {
        assertEquals(NativeInputGate.Verdict.NONE, NativeInputGate.probe(null, null, null, null, null));
    }
}
