package com.ziggfreed.common.encounter.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.ziggfreed.common.encounter.event.ResetReason;
import com.ziggfreed.common.encounter.signal.EncounterSignal;

/**
 * A fight's escape end, on the pure parts a unit JVM can hold (it cannot stand up the signal system
 * or a store): the reset reason and the moment word are appended, so every earlier one keeps its
 * place, and a run's escape is latched once and never carried into the run that follows it.
 */
class EscapedEndTest {

    @Test
    void theEscapeReasonAndMomentAreAppendedLast() {
        assertEquals("ESCAPED", ResetReason.ESCAPED.name());
        assertEquals(ResetReason.values().length - 1, ResetReason.ESCAPED.ordinal(),
                "a reason is only ever appended, so every earlier one keeps its place");
        assertEquals("escaped", EncounterSignal.Moment.ESCAPED.word());
        assertEquals(EncounterSignal.Moment.values().length - 1, EncounterSignal.Moment.ESCAPED.ordinal(),
                "a moment is only ever appended, so every earlier one keeps its place");
    }

    @Test
    void anEscapeLatchesOnceAndTheNextRunStartsClean() {
        ZigEncounterRun run = new ZigEncounterRun();
        assertFalse(run.isEscaped());
        assertTrue(run.markEscaped(), "the first beat latches the escape");
        assertFalse(run.markEscaped(), "a second beat on the same run does nothing");
        assertTrue(run.isEscaped());

        run.rollOver();
        assertFalse(run.isEscaped(), "the run that follows has not escaped");
    }
}
