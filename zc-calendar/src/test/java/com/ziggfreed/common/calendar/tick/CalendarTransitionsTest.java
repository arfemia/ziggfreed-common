package com.ziggfreed.common.calendar.tick;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.ziggfreed.common.occurrence.Occurrence;

/** What changed between two looks at the calendar, and how each change is told. */
class CalendarTransitionsTest {

    private static final Occurrence EVE_2026 = new Occurrence("hallows_eve", 2026, 1_000L, 2_000L);
    private static final Occurrence EVE_2027 = new Occurrence("hallows_eve", 2027, 3_000L, 4_000L);
    private static final Occurrence MOON_2026 = new Occurrence("harvest_moon", 2026, 1_500L, 1_800L);

    @Test
    void aRunAppearingStartsAndOneVanishingEnds() {
        CalendarTick start = CalendarTransitions.diff(Map.of("hallows_eve", EVE_2026),
                Map.of("hallows_eve", EVE_2026, "harvest_moon", MOON_2026), id -> true, 1_500L, false);
        assertEquals(List.of(new CalendarTick.Started(MOON_2026, false)), start.started());
        assertTrue(start.ended().isEmpty());
        CalendarTick end = CalendarTransitions.diff(Map.of("hallows_eve", EVE_2026, "harvest_moon", MOON_2026),
                Map.of("hallows_eve", EVE_2026), id -> true, 1_800L, false);
        assertEquals(List.of(new CalendarTick.Ended(MOON_2026, false)), end.ended());
        assertEquals(Set.of("hallows_eve"), end.live());
    }

    @Test
    void nothingChangedIsNoTransition() {
        CalendarTick tick = CalendarTransitions.diff(Map.of("hallows_eve", EVE_2026), Map.of("hallows_eve", EVE_2026),
                id -> true, 1_200L, false);
        assertFalse(tick.changed());
    }

    @Test
    void aRunVanishingWhileItsEventIsSwitchedOffEndsAsASwitchOff() {
        CalendarTick tick = CalendarTransitions.diff(Map.of("hallows_eve", EVE_2026), Map.of(), id -> false, 1_200L, false);
        assertEquals(List.of(new CalendarTick.Ended(EVE_2026, true)), tick.ended());
    }

    @Test
    void atBootEveryRunningEventResumes() {
        CalendarTick tick = CalendarTransitions.diff(Map.of(), Map.of("hallows_eve", EVE_2026), id -> true, 1_200L, true);
        assertTrue(tick.booting());
        assertEquals(List.of(new CalendarTick.Started(EVE_2026, true)), tick.started());
    }

    @Test
    void aNewYearsRunEndsTheOldOneAndStartsTheNew() {
        CalendarTick tick = CalendarTransitions.diff(Map.of("hallows_eve", EVE_2026), Map.of("hallows_eve", EVE_2027),
                id -> true, 3_000L, false);
        assertEquals(List.of(new CalendarTick.Ended(EVE_2026, false)), tick.ended());
        assertEquals(List.of(new CalendarTick.Started(EVE_2027, false)), tick.started());
    }

    @Test
    void aYearsNextRunEndsTheOneBeforeAndStartsItselfButMovedDaysAreNoTransition() {
        Occurrence spring = new Occurrence("two_fairs", 2026, 1, 1_000L, 2_000L);
        Occurrence autumn = new Occurrence("two_fairs", 2026, 2, 2_000L, 3_000L);
        CalendarTick tick = CalendarTransitions.diff(Map.of("two_fairs", spring), Map.of("two_fairs", autumn),
                id -> true, 2_000L, false);
        assertEquals(List.of(new CalendarTick.Ended(spring, false)), tick.ended(), "back to back, one tick");
        assertEquals(List.of(new CalendarTick.Started(autumn, false)), tick.started());
        CalendarTick moved = CalendarTransitions.diff(Map.of("two_fairs", autumn),
                Map.of("two_fairs", new Occurrence("two_fairs", 2026, 2, 1_500L, 3_500L)), id -> true, 2_100L, false);
        assertFalse(moved.changed(), "a run whose days moved is the same run: no end, no start");
    }
}
