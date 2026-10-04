package com.ziggfreed.common.calendar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

/** A month-day window: both days in, crossing the new year, counted in a zone. */
class AnnualWindowTest {

    private static final ZoneId UTC = ZoneOffset.UTC;

    private static long at(String instant) {
        return Instant.parse(instant).toEpochMilli();
    }

    @Test
    void bothDaysAreInsideTheWindow() {
        AnnualWindow window = AnnualWindow.parse("10-01", "11-03");
        assertNotNull(window);
        assertEquals(at("2026-10-01T00:00:00Z"), window.startMs(2026, UTC));
        assertEquals(at("2026-11-04T00:00:00Z"), window.endMs(2026, UTC),
                "the last day is in, so the run ends at the next midnight");
        assertEquals(Integer.valueOf(2026), window.yearContaining(at("2026-10-01T00:00:00Z"), UTC));
        assertEquals(Integer.valueOf(2026), window.yearContaining(at("2026-11-03T23:59:59Z"), UTC));
        assertNull(window.yearContaining(at("2026-11-04T00:00:00Z"), UTC));
        assertNull(window.yearContaining(at("2026-09-30T23:59:59Z"), UTC));
        assertFalse(window.crossesNewYear());
        assertEquals("10-01..11-03", window.toString());
    }

    @Test
    void aOneDayWindowRunsThatWholeDay() {
        AnnualWindow window = AnnualWindow.parse("10-31", "10-31");
        assertNotNull(window);
        assertEquals(at("2026-10-31T00:00:00Z"), window.startMs(2026, UTC));
        assertEquals(at("2026-11-01T00:00:00Z"), window.endMs(2026, UTC));
    }

    @Test
    void aWindowCrossingTheNewYearBelongsToTheYearItStarts() {
        AnnualWindow window = AnnualWindow.parse("12-20", "01-05");
        assertNotNull(window);
        assertTrue(window.crossesNewYear());
        assertEquals(Integer.valueOf(2026), window.yearContaining(at("2026-12-25T12:00:00Z"), UTC));
        assertEquals(Integer.valueOf(2026), window.yearContaining(at("2027-01-05T23:00:00Z"), UTC),
                "early January is still the run that began in December");
        assertNull(window.yearContaining(at("2027-01-06T00:00:00Z"), UTC));
        assertEquals(at("2027-01-06T00:00:00Z"), window.endMs(2026, UTC));
    }

    @Test
    void aZoneMovesEveryBoundaryToItsOwnMidnight() {
        AnnualWindow window = AnnualWindow.parse("10-29", "10-31");
        assertNotNull(window);
        ZoneId newYork = ZoneId.of("America/New_York");
        assertEquals(at("2026-10-29T04:00:00Z"), window.startMs(2026, newYork),
                "midnight in New York is 04:00 UTC while daylight time runs");
        assertEquals(at("2026-11-01T04:00:00Z"), window.endMs(2026, newYork));
    }

    @Test
    void february29thFallsBackToThe28thInAYearWithoutOne() {
        AnnualWindow window = AnnualWindow.parse("02-29", "02-29");
        assertNotNull(window);
        assertEquals(at("2027-02-28T00:00:00Z"), window.startMs(2027, UTC));
        assertEquals(at("2028-02-29T00:00:00Z"), window.startMs(2028, UTC));
    }

    @Test
    void onlyRealMonthDaysParse() {
        assertNull(AnnualWindow.parse("1-5", "11-03"), "two digits each");
        assertNull(AnnualWindow.parse("02-30", "03-01"));
        assertNull(AnnualWindow.parse("13-01", "12-31"));
        assertNull(AnnualWindow.parse(null, "12-31"));
        assertNotNull(AnnualWindow.parse(" 10-01 ", "11-03"), "surrounding spaces are forgiven");
    }

    @Test
    void aClockIsAZoneIdOrAnOffsetAndUtcByDefault() {
        assertEquals(ZoneOffset.UTC, AnnualWindow.zone(null));
        assertEquals(ZoneOffset.UTC, AnnualWindow.zone("  "));
        assertEquals(ZoneId.of("Europe/Paris"), AnnualWindow.zone("Europe/Paris"));
        assertEquals(ZoneOffset.ofHours(2), AnnualWindow.zone("+02:00"));
        assertNull(AnnualWindow.zone("Mars/Olympus_Mons"));
    }

    @Test
    void theNextStartIsStrictlyAfterNowAndNeverBeforeTheFirstYear() {
        AnnualWindow window = AnnualWindow.parse("10-01", "11-03");
        assertNotNull(window);
        assertEquals(at("2027-10-01T00:00:00Z"), window.nextStartMs(at("2026-10-01T00:00:00Z"), UTC, 2026),
                "a run starting this very instant is not the next one");
        assertEquals(at("2026-10-01T00:00:00Z"), window.nextStartMs(at("2026-06-01T00:00:00Z"), UTC, 2026));
        assertEquals(at("2030-10-01T00:00:00Z"), window.nextStartMs(at("2026-06-01T00:00:00Z"), UTC, 2030));
    }
}
