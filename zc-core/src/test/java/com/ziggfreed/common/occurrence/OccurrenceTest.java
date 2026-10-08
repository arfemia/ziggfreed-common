package com.ziggfreed.common.occurrence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/** A run is (event, year, number): the old four-number form is run 1, and moved days are still the same run. */
class OccurrenceTest {

    @Test
    void aRunIsItsEventYearAndNumberAndTheOldFormIsRunOne() {
        Occurrence spring = new Occurrence("Spring_Fair", 2026, 100L, 200L);
        assertEquals(1, spring.number(), "an event that comes round once a year only has run 1");
        Occurrence autumn = new Occurrence("spring_fair", 2026, 2, 300L, 400L);
        assertFalse(spring.sameRun(autumn), "the year's second run is a run of its own");
        assertTrue(autumn.sameRun(new Occurrence("SPRING_FAIR", 2026, 2, 350L, 450L)),
                "its days moved, it is the same run");
        assertFalse(spring.sameRun(null));
        assertFalse(autumn.sameRun(new Occurrence("other_fair", 2026, 2, 300L, 400L)), "another event's run");
        assertFalse(autumn.sameRun(new Occurrence("spring_fair", 2027, 2, 300L, 400L)), "another year's run");
        assertEquals("2026", spring.label());
        assertEquals("2026#2", autumn.label());
        assertTrue(Occurrence.IN_ORDER.compare(spring, autumn) < 0);
        assertTrue(Occurrence.IN_ORDER.compare(autumn, new Occurrence("spring_fair", 2027, 1, 500L, 600L)) < 0,
                "a year's last run comes before the next year's first");
        assertThrows(IllegalArgumentException.class, () -> new Occurrence("spring_fair", 2026, 0, 1L, 2L),
                "runs count from 1");
    }

    // A run's number names it; it is no count and no place in time: a list of spans keeps its written order, and a
    // monthly or weekly run's number is its month or week, so numbers skip. Time order is by start.
    @Test
    void runsComeRoundInTheOrderTheyStartWhateverTheirNumbers() {
        Occurrence writtenSecond = new Occurrence("fair", 2026, 2, 100L, 200L);
        Occurrence writtenFirst = new Occurrence("fair", 2026, 1, 300L, 400L);
        Occurrence december = new Occurrence("fair", 2026, 12, 500L, 600L);
        Occurrence nextJanuary = new Occurrence("fair", 2027, 1, 700L, 800L);
        List<Occurrence> runs = new ArrayList<>(List.of(nextJanuary, december, writtenFirst, writtenSecond));
        runs.sort(Occurrence.IN_ORDER);
        assertEquals(List.of(writtenSecond, writtenFirst, december, nextJanuary), runs,
                "the run written second but dated first comes round first");
        assertEquals("2026#12", december.label(), "a label names the run by its number, never its count");
    }
}
