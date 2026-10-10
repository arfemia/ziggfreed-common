package com.ziggfreed.common.instance.leaderboard.statboard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.ziggfreed.common.instance.leaderboard.Leaderboard;
import com.ziggfreed.common.occurrence.Occurrence;
import com.ziggfreed.common.occurrence.OccurrenceSource;

/**
 * The stat board's window math: which week and month an instant falls in on a period's clock, across the
 * UTC day boundary, the Monday rollover, a zone either side of UTC and the turn of the year, the season
 * runs a calendar has live, and the buckets a period writes and reads. A new window's key is the reset, so
 * a key that turns a moment early or late empties a tab at the wrong time, and one that sorts out of time
 * order makes the board drop the running window. Pure {@code java.time} and a fixed calendar; no server.
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

    @Test
    void aSeasonRunKeySortsAsTextInTimeOrder() {
        assertEquals("hallows_eve:2026", PeriodWindows.runWindow("Hallows_Eve", 2026, 1),
                "a year's first run is the year alone");
        assertEquals("hallows_eve:2026#09", PeriodWindows.runWindow("hallows_eve", 2026, 9),
                "a later run's number is two digits");
        List<String> inOrder = List.of(
                PeriodWindows.runWindow("hallows_eve", 2025, 1),
                PeriodWindows.runWindow("hallows_eve", 2026, 1),
                PeriodWindows.runWindow("hallows_eve", 2026, 2),
                PeriodWindows.runWindow("hallows_eve", 2026, 9),
                PeriodWindows.runWindow("hallows_eve", 2026, 10),
                PeriodWindows.runWindow("hallows_eve", 2026, 54),
                PeriodWindows.runWindow("hallows_eve", 2027, 1));
        List<String> sorted = new ArrayList<>(inOrder);
        Collections.reverse(sorted);
        Collections.sort(sorted);
        assertEquals(inOrder, sorted, "one event's runs sort as text by year, then by number");

        assertEquals(new PeriodWindows.Run("hallows_eve", 2026, 9), PeriodWindows.parseRunWindow("hallows_eve:2026#09"));
        assertEquals(new PeriodWindows.Run("hallows_eve", 2026, 1), PeriodWindows.parseRunWindow("hallows_eve:2026"));
        assertNull(PeriodWindows.parseRunWindow("2026-W42"), "a week is no run");

        StatPeriod season = season("season", null);
        assertEquals("season@hallows_eve:2026", season.runKey("hallows_eve", 2026, 1));
        assertEquals("season@hallows_eve:", season.runPrefix("hallows_eve"));
        assertFalse(season.runKey("hallows_eve", 2026, 2).contains("|"), "a bucket key never carries |");
        assertNull(season.windowPrefix(), "a season drops its old runs per event, never all at once");
    }

    @Test
    void aSeasonWritesEachLiveRunAndTurnsOverAtItsNextRun() {
        StatPeriod every = season("season", null);
        StatPeriod hallows = season("hallows", "hallows_eve");

        assertEquals(List.of(new StatPeriod.Window("season@hallows_eve:2026", "season@hallows_eve:")),
                windows(every, "2026-10-10T12:00:00Z"));
        assertEquals(List.of("season@hallows_eve:2026", "season@harvest_moon:2026"),
                keys(windows(every, "2026-10-30T12:00:00Z")), "every live event, each in its own bucket");
        assertEquals(List.of("hallows@hallows_eve:2026"), keys(windows(hallows, "2026-10-30T12:00:00Z")),
                "a season naming its event follows that one alone");
        assertEquals(List.of(), windows(every, "2026-11-13T00:00:00Z"), "nothing between runs");
        assertNull(every.writeKey(at("2026-10-10T12:00:00Z")), "a season's buckets come from the calendar");

        assertEquals(List.of(), windows(every, "2027-09-30T23:59:59Z"));
        assertEquals(List.of(new StatPeriod.Window("season@hallows_eve:2027", "season@hallows_eve:")),
                windows(every, "2027-10-01T00:00:00Z"), "the next run's key is the reset");

        // The board keeps each event's newest Keep runs by key alone, and never touches another event's.
        Leaderboard board = new Leaderboard("season-test", task -> { });
        UUID player = UUID.fromString("00000000-0000-0000-0000-000000000001");
        for (String bucket : List.of("season@hallows_eve:2026", "season@hallows_eve:2025",
                "season@harvest_moon:2026", "season@hallows_eve:2027")) {
            board.addStats(bucket, player, "Pip", Map.of("Mob_Kills", 1L));
        }
        assertEquals(1, board.retain(every.runPrefix("hallows_eve"), every.keep()));
        assertEquals(Set.of("season@hallows_eve:2026", "season@hallows_eve:2027", "season@harvest_moon:2026"),
                board.bucketKeys());
    }

    @Test
    void aSeasonsRunStillReadsAfterItEnds() {
        StatPeriod every = season("season", null);
        Set<String> held = Set.of("season@hallows_eve:2025", "season@hallows_eve:2026", "season@harvest_moon:2026",
                "weekly@2026-W49", "season@gone_event:2026", "range@launch");

        assertEquals(List.of("season@hallows_eve:2026", "season@hallows_eve:2025", "season@harvest_moon:2026"),
                every.readKeys(at("2026-12-01T00:00:00Z"), RUNS, held),
                "by event, newest run first; an event the server no longer loads shows none");
        assertEquals(List.of("season@hallows_eve:2027", "season@hallows_eve:2026", "season@hallows_eve:2025",
                        "season@harvest_moon:2026"), every.readKeys(at("2027-10-02T00:00:00Z"), RUNS, held),
                "a live run reads before anyone has written it");
        assertEquals(List.of("hallows@hallows_eve:2026"),
                season("hallows", "hallows_eve").readKeys(at("2026-10-30T12:00:00Z"), RUNS, held));
    }

    /**
     * Hallow's Eve, October 1 to November 12 of every year, and Harvest Moon, October 28 to November 1 of 2026
     * only, both in UTC: two events live side by side, as a season pack runs them.
     */
    private static final OccurrenceSource RUNS = new OccurrenceSource() {
        @Override
        public boolean isEnabled(String eventId) {
            return currentYear(eventId, 0L) != null;
        }

        @Override
        public Occurrence live(String eventId, long nowMs) {
            int year = Instant.ofEpochMilli(nowMs).atZone(UTC).getYear();
            return switch (eventId) {
                case "hallows_eve" -> runAt(eventId, year, "-10-01", "-11-13", nowMs);
                case "harvest_moon" -> year == 2026 ? runAt(eventId, year, "-10-28", "-11-02", nowMs) : null;
                default -> null;
            };
        }

        @Override
        public List<Occurrence> history(String eventId, long nowMs) {
            return List.of();
        }

        @Override
        public Map<String, Occurrence> liveAll(long nowMs) {
            Map<String, Occurrence> out = new TreeMap<>();
            for (String id : List.of("hallows_eve", "harvest_moon")) {
                Occurrence run = live(id, nowMs);
                if (run != null) {
                    out.put(id, run);
                }
            }
            return out;
        }

        @Override
        public Integer currentYear(String eventId, long nowMs) {
            return eventId.equals("hallows_eve") || eventId.equals("harvest_moon")
                    ? Instant.ofEpochMilli(nowMs).atZone(UTC).getYear() : null;
        }
    };

    private static Occurrence runAt(String eventId, int year, String start, String end, long nowMs) {
        long startMs = at(year + start + "T00:00:00Z").toEpochMilli();
        long endMs = at(year + end + "T00:00:00Z").toEpochMilli();
        return nowMs >= startMs && nowMs < endMs ? new Occurrence(eventId, year, startMs, endMs) : null;
    }

    /** {@code period}'s windows at {@code utc}, out of what the fixed calendar has live then, as a write asks. */
    private static List<StatPeriod.Window> windows(StatPeriod period, String utc) {
        Instant now = at(utc);
        return period.windows(now, RUNS.liveAll(now.toEpochMilli()));
    }

    private static List<String> keys(List<StatPeriod.Window> windows) {
        return windows.stream().map(StatPeriod.Window::key).toList();
    }

    private static StatPeriod season(String id, String event) {
        return new StatPeriod(id, PeriodKind.SEASON_RUN, null, 0, UTC, DayOfWeek.MONDAY, 2, null, null, event);
    }

    private static StatPeriod period(String id, PeriodKind kind, LocalDateTime from, LocalDateTime to) {
        return new StatPeriod(id, kind, null, 0, UTC, DayOfWeek.MONDAY, 2, from, to, null);
    }
}
