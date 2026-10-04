package com.ziggfreed.common.achievement.asset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ziggfreed.common.occurrence.Occurrence;
import com.ziggfreed.common.occurrence.OccurrenceSource;
import com.ziggfreed.common.occurrence.Occurrences;

/**
 * Recon gate G10, kept as a contract: the zc-core slot the achievement fold reads a calendar through,
 * under the names this module reads. The calendar module fills it at setup; this module never sees
 * that module. A rename on either side fails here, at compile, rather than as a server with no yearly
 * copies; and the answer this module leans on hardest, that an event its owner switched off still
 * knows its years, fails here rather than as earned trophies vanishing.
 */
class OccurrenceSeamContractTest {

    private static final String EVENT = "yourmod_festival";

    @BeforeEach
    @AfterEach
    void emptyTheSlot() {
        Occurrences.resetForTests();
    }

    @Test
    void anUnfilledSlotKnowsNoYearsAndNoRun() {
        OccurrenceSource calendar = Occurrences.source();

        assertNull(calendar.firstYear(EVENT));
        assertNull(calendar.currentYear(EVENT, 0L));
        assertNull(calendar.live(EVENT, 0L));
    }

    @Test
    void aFilledSlotAnswersThroughWhatFilledIt() {
        Occurrences.fill(new FakeCalendar().event(EVENT, 2024, 2026).live(EVENT, 2026));

        OccurrenceSource calendar = Occurrences.source();
        assertEquals(Integer.valueOf(2024), calendar.firstYear(EVENT));
        assertEquals(Integer.valueOf(2026), calendar.currentYear(EVENT, 0L));
        Occurrence run = calendar.live(EVENT, 0L);
        assertNotNull(run);
        assertEquals(2026, run.year(), "the run going on belongs to the year it started in");
    }

    @Test
    void aSwitchedOffEventKeepsItsYearsButHasNoRun() {
        Occurrences.fill(new FakeCalendar().event(EVENT, 2024, 2026).live(EVENT, 2026).switchedOff(EVENT));

        OccurrenceSource calendar = Occurrences.source();
        assertFalse(calendar.isEnabled(EVENT), "off means absent to every run question");
        assertNull(calendar.live(EVENT, 0L));
        assertEquals(Integer.valueOf(2024), calendar.firstYear(EVENT),
                "the years look past the switch, so every copy a player earned keeps existing");
        assertEquals(Integer.valueOf(2026), calendar.currentYear(EVENT, 0L));
    }
}
