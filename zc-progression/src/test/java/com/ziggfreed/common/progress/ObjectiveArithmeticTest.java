package com.ziggfreed.common.progress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The one compare both engines run: which arithmetic a kind asks for, and the ceiling reading in
 * particular - met at or under the authored amount, untouched above it, and never born completed
 * at a zero ceiling.
 */
class ObjectiveArithmeticTest {

    private static final ObjectiveKind COUNT = ObjectiveKind.of("KILL_THINGS");
    private static final ObjectiveKind HIGH_WATER = ObjectiveKind.valueBased("REACH_THING");
    private static final ObjectiveKind CEILING = ObjectiveKind.atMost("CLEAR_SECONDS");

    private static ObjectiveDef objective(long amount) {
        return ObjectiveDef.builder("step", "whatever").amount(amount).build();
    }

    // ==================== which arithmetic ====================

    @Test
    void anAccumulatingKindAddsWhatWasFired() {
        ObjectiveDef def = objective(5);
        ObjectiveProgressState state = ObjectiveArithmetic.fresh(COUNT, def);
        assertFalse(ObjectiveArithmetic.apply(COUNT, def, state, 2));
        assertFalse(ObjectiveArithmetic.apply(COUNT, def, state, 2));
        assertEquals(4, state.current());
        assertTrue(ObjectiveArithmetic.apply(COUNT, def, state, 1), "the call that crosses the line reports it");
    }

    @Test
    void aValueBasedKindKeepsTheHighWaterMark() {
        ObjectiveDef def = objective(10);
        ObjectiveProgressState state = ObjectiveArithmetic.fresh(HIGH_WATER, def);
        ObjectiveArithmetic.apply(HIGH_WATER, def, state, 5);
        ObjectiveArithmetic.apply(HIGH_WATER, def, state, 4);
        assertEquals(5, state.current(), "a lower reading never sums onto a higher one");
        assertTrue(ObjectiveArithmetic.apply(HIGH_WATER, def, state, 12));
    }

    @Test
    void anUnknownKindReadsAsAccumulating() {
        ObjectiveDef def = objective(2);
        ObjectiveProgressState state = ObjectiveArithmetic.fresh(null, def);
        assertEquals(2, state.required(), "sized by the authored amount, as a bare registration would be");
        assertFalse(ObjectiveArithmetic.apply(null, def, state, 1));
        assertTrue(ObjectiveArithmetic.apply(null, def, state, 1));
    }

    // ==================== the ceiling ====================

    @Test
    void aCeilingIsMetAtOrUnderTheAuthoredAmountAndUntouchedAbove() {
        ObjectiveDef under300 = objective(300);
        ObjectiveProgressState state = ObjectiveArithmetic.fresh(CEILING, under300);
        assertFalse(ObjectiveArithmetic.apply(CEILING, under300, state, 420), "over the ceiling moves nothing");
        assertEquals(0, state.current());
        assertFalse(state.isCompleted());
        assertTrue(ObjectiveArithmetic.apply(CEILING, under300, state, 300), "exactly the ceiling is met");
        assertTrue(state.isCompleted());
    }

    @Test
    void aCeilingRecordsMetOrNotRatherThanTheValue() {
        ObjectiveDef under300 = objective(300);
        ObjectiveProgressState state = ObjectiveArithmetic.fresh(CEILING, under300);
        assertEquals(1, state.required(), "a ceiling counts to one: met");
        assertEquals("0/1", state.serialize());
        ObjectiveArithmetic.apply(CEILING, under300, state, 120);
        assertEquals("1/1", state.serialize(), "the wire form carries met, never the seconds");
    }

    @Test
    void aZeroCeilingIsNotBornCompleted() {
        ObjectiveDef noDeaths = objective(0);
        ObjectiveProgressState state = ObjectiveArithmetic.fresh(CEILING, noDeaths);
        assertFalse(state.isCompleted(), "a 'no deaths' step must wait for the fight to end");
        assertFalse(ObjectiveArithmetic.apply(CEILING, noDeaths, state, 2), "two deaths is over the ceiling");
        assertTrue(ObjectiveArithmetic.apply(CEILING, noDeaths, state, 0), "zero deaths meets it");
    }

    @Test
    void aStoredCeilingReadsBackAsMetOrNot() {
        ObjectiveDef under300 = objective(300);
        assertTrue(ObjectiveArithmetic.stored(CEILING, under300, 1).isCompleted());
        assertFalse(ObjectiveArithmetic.stored(CEILING, under300, 0).isCompleted());
        assertEquals(300, ObjectiveArithmetic.stored(HIGH_WATER, under300, 7).required(),
                "a high-water step is still sized by its amount");
    }

    @Test
    void aFinishedStateIsLeftAloneWhateverArrives() {
        ObjectiveDef def = objective(1);
        ObjectiveProgressState done = ObjectiveArithmetic.stored(COUNT, def, 1);
        assertFalse(ObjectiveArithmetic.apply(COUNT, def, done, 1));
        assertFalse(ObjectiveArithmetic.applyStanding(HIGH_WATER, def, done, 5));
    }

    // ==================== a standing value ====================

    @Test
    void aStandingValueIsAHighWaterMarkForAnyNonCeilingKind() {
        ObjectiveDef def = objective(10);
        ObjectiveProgressState state = ObjectiveArithmetic.fresh(COUNT, def);
        ObjectiveArithmetic.applyStanding(COUNT, def, state, 6);
        ObjectiveArithmetic.applyStanding(COUNT, def, state, 6);
        assertEquals(6, state.current(), "what a player already holds is read, never added twice");
    }

    @Test
    void aStandingValueUnderACeilingIsComparedLikeAFiredOne() {
        ObjectiveDef under300 = objective(300);
        ObjectiveProgressState state = ObjectiveArithmetic.fresh(CEILING, under300);
        assertFalse(ObjectiveArithmetic.applyStanding(CEILING, under300, state, 400));
        assertTrue(ObjectiveArithmetic.applyStanding(CEILING, under300, state, 250));
    }

    @Test
    void onlyAValueBasedKindCanBeACeiling() {
        assertTrue(ObjectiveArithmetic.isCeiling(CEILING));
        assertFalse(ObjectiveArithmetic.isCeiling(COUNT.withAtMost(true)),
                "at-most means nothing on an accumulating kind, so the knob composes rather than modes");
        assertFalse(ObjectiveArithmetic.isCeiling(null));
    }
}
