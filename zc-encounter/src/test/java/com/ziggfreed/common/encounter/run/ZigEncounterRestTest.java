package com.ziggfreed.common.encounter.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;

/**
 * The three answers a {@code ZigRested} sensor gives, on the pure question behind it (the engine's
 * sensor types cannot be stood up in a unit JVM), and a copy keeping the rest. The component's save
 * (its version leaf, a save from before it, a newer one refused) is the root's {@code SavedRecordVersionTest}.
 */
class ZigEncounterRestTest {

    private static final Instant NOW = Instant.parse("2000-01-02T00:00:00Z");

    @Test
    void aSiteWithNothingStampedHasRested() {
        assertTrue(ZigEncounterRest.rested(null, NOW), "no component at all");
        assertTrue(ZigEncounterRest.rested(new ZigEncounterRest(), NOW), "a component with nothing stamped");
        assertEquals(0L, new ZigEncounterRest().secondsLeft(NOW));
    }

    @Test
    void aSiteWhoseRestIsAheadOfTheClockIsResting() {
        ZigEncounterRest rest = ZigEncounterRest.until(NOW.plus(Duration.ofDays(1)));
        assertFalse(ZigEncounterRest.rested(rest, NOW));
        assertFalse(rest.isRested(NOW.plusSeconds(86_399)));
        assertEquals(86_400L, rest.secondsLeft(NOW));
        assertEquals(1L, rest.secondsLeft(NOW.plusSeconds(86_399)));
    }

    @Test
    void aSiteWhoseRestTheClockHasReachedHasRested() {
        ZigEncounterRest rest = ZigEncounterRest.until(NOW);
        assertTrue(rest.isRested(NOW), "at the instant itself");
        assertTrue(ZigEncounterRest.rested(rest, NOW.plusSeconds(1)), "and after it");
        assertEquals(0L, rest.secondsLeft(NOW.plusSeconds(1)));
    }

    @Test
    void aCopyKeepsTheRest() {
        ZigEncounterRest rest = ZigEncounterRest.until(NOW);
        assertEquals(NOW, ((ZigEncounterRest) rest.clone()).restUntil());
    }
}
