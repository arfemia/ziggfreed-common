package com.ziggfreed.common.instance.leaderboard.statboard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

/**
 * The stat board's window math: which week and month an instant falls in on a period's clock, across the
 * UTC day boundary, the Monday rollover, a zone either side of UTC and the turn of the year, and the
 * buckets a period writes and reads. A new window's key is the reset, so a key that turns a moment early
 * or late empties a tab at the wrong time. Pure {@code java.time}; no server.
 */
class PeriodWindowsTest {

    private static final ZoneId UTC = ZoneOffset.UTC;
    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");
    private static final ZoneId TOKYO = ZoneId.of("Asia/Tokyo");

    private static Instant at(String utc) {
        return Instant.parse(utc);
    }

    @Test
    void aUtcWeekTurnsAtMondayMidnight() {
        assertEquals("2026-W41", PeriodWindows.weekKey(at("2026-10-11T23:59:59Z"), UTC, DayOfWeek.MONDAY),
                "Sunday's last second is still last week");
        assertEquals("2026-W42", PeriodWindows.weekKey(at("2026-10-12T00:00:00Z"), UTC, DayOfWeek.MONDAY),
                "Monday midnight starts the next");
    }

    @Test
    void aWeekTurnsOnItsOwnZonesClockNotOnUtcs() {
        // Monday 00:00 UTC is still Sunday evening in New York.
        assertEquals("2026-W41", PeriodWindows.weekKey(at("2026-10-12T03:59:59Z"), NEW_YORK, DayOfWeek.MONDAY));
        assertEquals("2026-W42", PeriodWindows.weekKey(at("2026-10-12T04:00:00Z"), NEW_YORK, DayOfWeek.MONDAY));
        // Sunday 15:00 UTC is already Monday in Tokyo.
        assertEquals("2026-W41", PeriodWindows.weekKey(at("2026-10-11T15:00:00Z"), UTC, DayOfWeek.MONDAY));
        assertEquals("2026-W42", PeriodWindows.weekKey(at("2026-10-11T15:00:00Z"), TOKYO, DayOfWeek.MONDAY));
    }

    @Test
    void aWeekCountsLikeIsoAcrossTheTurnOfTheYear() {
        assertEquals("2026-W53", PeriodWindows.weekKey(at("2026-12-31T12:00:00Z"), UTC, DayOfWeek.MONDAY));
        assertEquals("2026-W53", PeriodWindows.weekKey(at("2027-01-03T23:59:59Z"), UTC, DayOfWeek.MONDAY),
                "the first Sunday of 2027 still closes 2026's last week");
        assertEquals("2027-W01", PeriodWindows.weekKey(at("2027-01-04T00:00:00Z"), UTC, DayOfWeek.MONDAY));
    }

    @Test
    void aWeekCanStartOnSunday() {
        String saturday = PeriodWindows.weekKey(at("2026-10-10T23:59:59Z"), UTC, DayOfWeek.SUNDAY);
        String sunday = PeriodWindows.weekKey(at("2026-10-11T00:00:00Z"), UTC, DayOfWeek.SUNDAY);
        String nextSaturday = PeriodWindows.weekKey(at("2026-10-17T23:59:59Z"), UTC, DayOfWeek.SUNDAY);
        assertNotEquals(saturday, sunday, "Sunday midnight starts a Sunday week");
        assertEquals(sunday, nextSaturday, "and it runs to Saturday's last second");
        assertEquals(PeriodWindows.weekKey(at("2026-10-12T00:00:00Z"), UTC, DayOfWeek.SUNDAY), sunday,
                "Monday is no turnover for a Sunday week");
    }

    @Test
    void aMonthTurnsAtMidnightOfItsFirstDayOnItsZonesClock() {
        assertEquals("2026-10", PeriodWindows.monthKey(at("2026-10-31T23:59:59Z"), UTC));
        assertEquals("2026-11", PeriodWindows.monthKey(at("2026-11-01T00:00:00Z"), UTC));
        assertEquals("2026-10", PeriodWindows.monthKey(at("2026-11-01T03:00:00Z"), NEW_YORK),
                "still October 31 in New York");
        assertEquals("2026-11", PeriodWindows.monthKey(at("2026-10-31T15:00:00Z"), TOKYO),
                "already November 1 in Tokyo");
        assertEquals("2026-12", PeriodWindows.monthKey(at("2026-12-31T23:59:59Z"), UTC));
        assertEquals("2027-01", PeriodWindows.monthKey(at("2027-01-01T00:00:00Z"), UTC));
    }

    @Test
    void windowKeysSortAsTextInTimeOrder() {
        String w09 = PeriodWindows.weekKey(at("2026-03-01T12:00:00Z"), UTC, DayOfWeek.MONDAY);
        String w10 = PeriodWindows.weekKey(at("2026-03-02T12:00:00Z"), UTC, DayOfWeek.MONDAY);
        assertEquals("2026-W09", w09, "a week number is two digits");
        assertTrue(w09.compareTo(w10) < 0);
        assertTrue("2026-W53".compareTo(PeriodWindows.weekKey(at("2027-01-04T00:00:00Z"), UTC, DayOfWeek.MONDAY)) < 0);
        assertTrue(PeriodWindows.monthKey(at("2026-09-15T00:00:00Z"), UTC)
                .compareTo(PeriodWindows.monthKey(at("2026-10-15T00:00:00Z"), UTC)) < 0, "a month is two digits");
    }

    @Test
    void eachPeriodWritesAndReadsItsOwnBucket() {
        StatPeriod allTime = period("all_time", PeriodKind.ALL_TIME, null, null);
        StatPeriod weekly = period("weekly", PeriodKind.WEEKLY, null, null);
        StatPeriod monthly = period("monthly", PeriodKind.MONTHLY, null, null);
        Instant now = at("2026-10-12T00:00:00Z");

        assertEquals("all_time", allTime.writeKey(now));
        assertEquals("weekly@2026-W42", weekly.writeKey(now));
        assertEquals("monthly@2026-10", monthly.writeKey(now));
        assertEquals(weekly.writeKey(now), weekly.readKey(now), "a week's tab reads the window it writes");
        assertEquals("weekly@", weekly.windowPrefix());
        assertNull(allTime.windowPrefix(), "the period that never ends has no old windows");
    }

    @Test
    void aRangeWritesOnlyBetweenItsDatesAndReadsFromItsStartOn() {
        StatPeriod launch = period("launch", PeriodKind.RANGE,
                LocalDateTime.parse("2026-10-12T00:00"), LocalDateTime.parse("2026-10-19T00:00"));

        assertNull(launch.writeKey(at("2026-10-11T23:59:59Z")));
        assertNull(launch.readKey(at("2026-10-11T23:59:59Z")), "no tab before it starts");
        assertEquals("range@launch", launch.writeKey(at("2026-10-12T00:00:00Z")));
        assertNull(launch.writeKey(at("2026-10-19T00:00:00Z")), "To is left out");
        assertEquals("range@launch", launch.readKey(at("2026-10-19T00:00:00Z")), "still readable after it ends");
        assertNull(launch.windowPrefix(), "a range is one window");
    }

    private static StatPeriod period(String id, PeriodKind kind, LocalDateTime from, LocalDateTime to) {
        return new StatPeriod(id, kind, null, 0, UTC, DayOfWeek.MONDAY, 2, from, to);
    }
}
