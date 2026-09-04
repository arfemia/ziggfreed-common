package com.ziggfreed.common.encounter.signal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The reserved {@code zc:<moment>[:<detail>]} grammar, parsed exactly as a script writes it. */
class EncounterSignalTest {

    @Test
    void aSignalWithoutThePrefixIsNotOurs() {
        assertNull(EncounterSignal.parse("test_signal_complete"));
        assertNull(EncounterSignal.parse(null));
        assertNull(EncounterSignal.parse(""));
        assertFalse(EncounterSignal.isFrameworkSignal("zcengaged"));
    }

    @Test
    void theFiveReservedMomentsParse() {
        assertEquals(EncounterSignal.Moment.ENGAGED, EncounterSignal.parse("zc:engaged").moment());
        assertEquals(EncounterSignal.Moment.DEFEATED, EncounterSignal.parse("zc:defeated").moment());
        assertEquals(EncounterSignal.Moment.RESET, EncounterSignal.parse("zc:reset").moment());
        EncounterSignal phase = EncounterSignal.parse("zc:phase:Phase_2");
        assertEquals(EncounterSignal.Moment.PHASE, phase.moment());
        assertEquals("Phase_2", phase.detail());
        EncounterSignal wave = EncounterSignal.parse("zc:wave:first");
        assertEquals(EncounterSignal.Moment.WAVE, wave.moment());
        assertEquals("first", wave.detail());
        assertTrue(wave.isReserved());
    }

    @Test
    void aWaveWithoutALabelStillCounts() {
        EncounterSignal wave = EncounterSignal.parse("zc:wave");
        assertEquals(EncounterSignal.Moment.WAVE, wave.moment());
        assertNull(wave.detail());
    }

    @Test
    void thePhaseWordIsCaseInsensitiveButTheStateNameKeepsItsCase() {
        EncounterSignal signal = EncounterSignal.parse("ZC:Phase:Enraged_Form");
        assertNotNull(signal);
        assertEquals(EncounterSignal.Moment.PHASE, signal.moment());
        assertEquals("Enraged_Form", signal.detail());
    }

    @Test
    void aPhaseWithNoStateNameParsesWithNoDetail() {
        EncounterSignal signal = EncounterSignal.parse("zc:phase:");
        assertEquals(EncounterSignal.Moment.PHASE, signal.moment());
        assertNull(signal.detail());
    }

    @Test
    void anyOtherZcIdIsTheAuthorsOwnBeat() {
        EncounterSignal custom = EncounterSignal.parse("zc:shrine:lit");
        assertEquals(EncounterSignal.Moment.CUSTOM, custom.moment());
        assertEquals("shrine:lit", custom.detail());
        assertFalse(custom.isReserved());
        assertEquals("zc:shrine:lit", custom.raw());
    }

    @Test
    void thePhaseIdIsBuiltTheWayItIsParsed() {
        String id = EncounterSignal.phaseId("Phase_3");
        EncounterSignal back = EncounterSignal.parse(id);
        assertEquals(EncounterSignal.Moment.PHASE, back.moment());
        assertEquals("Phase_3", back.detail());
    }
}
