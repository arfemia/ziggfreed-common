package com.ziggfreed.common.progress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The progress arithmetic, the completion edge, and the wire form's round trip. */
class ObjectiveProgressStateTest {

    @Test
    void advanceAccumulatesAndClampsAtRequired() {
        ObjectiveProgressState state = new ObjectiveProgressState(0, 5);
        assertFalse(state.advance(2));
        assertEquals(2, state.current());
        assertTrue(state.advance(9), "the call that crosses the line reports completion");
        assertEquals(5, state.current(), "never counts past what was required");
        assertTrue(state.isCompleted());
    }

    @Test
    void completionIsReportedByExactlyOneCall() {
        ObjectiveProgressState state = new ObjectiveProgressState(0, 2);
        assertFalse(state.increment());
        assertTrue(state.increment());
        assertFalse(state.increment(), "a finished objective reports nothing further");
    }

    @Test
    void nonPositiveDeltasAreIgnored() {
        ObjectiveProgressState state = new ObjectiveProgressState(1, 5);
        assertFalse(state.advance(0));
        assertFalse(state.advance(-3));
        assertEquals(1, state.current());
    }

    @Test
    void applyValueKeepsTheHighWaterMarkAndNeverSums() {
        ObjectiveProgressState state = new ObjectiveProgressState(0, 10);
        state.applyValue(5);
        state.applyValue(4);
        assertEquals(5, state.current(), "a lower reading must not add to the recorded one");
        state.applyValue(7);
        assertEquals(7, state.current());
        assertTrue(state.applyValue(12));
        assertEquals(10, state.current());
    }

    @Test
    void alreadyMetInputIsBornCompleted() {
        assertTrue(new ObjectiveProgressState(5, 5).isCompleted());
        assertTrue(new ObjectiveProgressState(9, 5).isCompleted());
        assertFalse(new ObjectiveProgressState(4, 5).isCompleted());
    }

    @Test
    void stateRoundTripsThroughItsWireForm() {
        ObjectiveProgressState state = new ObjectiveProgressState(3, 7);
        assertEquals("3/7", state.serialize());
        ObjectiveProgressState back = ObjectiveProgressState.deserialize(state.serialize());
        assertEquals(3, back.current());
        assertEquals(7, back.required());
        assertFalse(back.isCompleted());
    }

    @Test
    void unreadableStateDecodesToAnUntouchedDefault() {
        for (String bad : new String[] {null, "", "nonsense", "/5", "x/y", "3"}) {
            ObjectiveProgressState state = ObjectiveProgressState.deserialize(bad);
            assertEquals(0, state.current(), "input: " + bad);
            assertEquals(1, state.required(), "input: " + bad);
        }
    }
}
