package com.ziggfreed.common.encounter.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;

/**
 * The three answers a {@code ZigRested} sensor gives, on the pure question behind it (the engine's
 * sensor types cannot be stood up in a unit JVM), and the component's codec round trip, the way the
 * chunk save carries it. Exercised through the {@code BuilderCodec} directly, never a live store.
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
    void theCodecRoundTripsTheInstantAndCarriesNothingForAnEmptyOne() throws IOException {
        Instant until = Instant.parse("2000-01-03T12:30:00Z");
        ExtraInfo info = new ExtraInfo();
        var bson = ZigEncounterRest.CODEC.encode(ZigEncounterRest.until(until), info);
        ZigEncounterRest decoded = ZigEncounterRest.CODEC.decode(bson, info);
        assertEquals(until, decoded.restUntil());

        var empty = ZigEncounterRest.CODEC.encode(new ZigEncounterRest(), info);
        assertTrue(empty.isEmpty(), "an unstamped rest writes no key");
        assertNull(ZigEncounterRest.CODEC.decode(empty, info).restUntil());

        ZigEncounterRest fromJson = ZigEncounterRest.CODEC.decodeJson(
                RawJsonReader.fromJsonString("{\"RestUntil\": \"2000-01-03T12:30:00Z\"}"), new ExtraInfo());
        assertEquals(until, fromJson.restUntil());
    }

    @Test
    void aCopyKeepsTheRest() {
        ZigEncounterRest rest = ZigEncounterRest.until(NOW);
        assertEquals(NOW, ((ZigEncounterRest) rest.clone()).restUntil());
    }
}
