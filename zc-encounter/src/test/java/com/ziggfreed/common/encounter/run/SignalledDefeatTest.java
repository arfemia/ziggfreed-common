package com.ziggfreed.common.encounter.run;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import com.ziggfreed.common.encounter.event.ResetReason;
import com.ziggfreed.common.encounter.run.SignalledDefeat.SubjectState;
import com.ziggfreed.common.encounter.run.SignalledDefeat.Verdict;

/**
 * What a script's {@code zc:defeated} beat settles a run as, on the pure question behind it (a unit
 * JVM cannot stand up the signal system or a store): the beat is the world's word only when the
 * subject reads dead. A living subject, or one gone without a death, was leashed and pays nothing;
 * a fight with no subject bound has nothing to check and keeps the beat as its defeat.
 */
class SignalledDefeatTest {

    @Test
    void aFightWithNoBoundSubjectKeepsTheBeatAsItsDefeat() {
        for (SubjectState state : SubjectState.values()) {
            assertEquals(Verdict.DEFEAT, SignalledDefeat.verdict(false, state),
                    "no subject to check, whatever the reading (" + state + ")");
        }
    }

    @Test
    void aDeadSubjectIsTheDefeat() {
        assertEquals(Verdict.DEFEAT, SignalledDefeat.verdict(true, SubjectState.DEAD));
    }

    @Test
    void aLiveSubjectIsALeashNotADefeat() {
        assertEquals(Verdict.LEASH, SignalledDefeat.verdict(true, SubjectState.ALIVE),
                "a boss past the script's Range still lives: nothing is paid");
    }

    @Test
    void aSubjectGoneWithoutADeathPaysNothing() {
        assertEquals(Verdict.LEASH, SignalledDefeat.verdict(true, SubjectState.GONE),
                "the death system settles every death it sees, so a vanished subject was never killed here");
    }

    @Test
    void theStateReadsTheWorldNotTheBeat() {
        assertEquals(SubjectState.GONE, SignalledDefeat.stateOf(false, false, 120.0F));
        assertEquals(SubjectState.GONE, SignalledDefeat.stateOf(false, true, 0.0F), "no entity, whatever else was read");
        assertEquals(SubjectState.DEAD, SignalledDefeat.stateOf(true, true, 50.0F), "the corpse still carries its death");
        assertEquals(SubjectState.DEAD, SignalledDefeat.stateOf(true, false, 0.0F), "no health left, the death not landed yet");
        assertEquals(SubjectState.DEAD, SignalledDefeat.stateOf(true, false, -3.0F));
        assertEquals(SubjectState.ALIVE, SignalledDefeat.stateOf(true, false, 0.5F));
        assertEquals(SubjectState.ALIVE, SignalledDefeat.stateOf(true, false, Float.NaN),
                "an unreadable health on a living entity is no kill");
    }

    @Test
    void aLeashEndsItsRunWithItsOwnReasonAppendedLast() {
        assertEquals("LEASHED", ResetReason.LEASHED.name());
        assertEquals(ResetReason.values().length - 1, ResetReason.LEASHED.ordinal(),
                "a reason is only ever appended, so every earlier one keeps its place");
    }
}
