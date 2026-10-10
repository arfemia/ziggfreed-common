package com.ziggfreed.common.almanac;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.ziggfreed.common.almanac.AlmanacCalendar.SeasonState;
import com.ziggfreed.common.counter.CounterMap;

/**
 * What one moment adds to a player's Almanac record: attendance, live, between seasons and absent. (A page's stat
 * lines count in the player stat store: their cases are zc-stats' {@code SeasonLinesTest}.)
 */
class AlmanacCounterTest {

    private static final AlmanacCalendar LIVE_2026 =
            id -> "test_season".equals(id) ? SeasonState.liveIn(2026) : null;
    private static final AlmanacCalendar BETWEEN =
            id -> "test_season".equals(id) ? SeasonState.BETWEEN : null;
    private static final AlmanacCalendar ABSENT = id -> null;

    @Test
    void attendanceForASeasonTheCalendarDoesNotAnswerForMarksNothing() {
        CounterMap tallies = new CounterMap();

        AlmanacCounter.count(tallies, ABSENT, "CALENDAR_ATTENDED", "Test_Season");

        assertTrue(tallies.isEmpty(), "off means absent");
    }

    @Test
    void attendanceMarksASeasonOnceAndCountsTheSeasonsAttended() {
        CounterMap tallies = new CounterMap();

        AlmanacCounter.count(tallies, LIVE_2026, "CALENDAR_ATTENDED", "Test_Season");
        AlmanacCounter.count(tallies, LIVE_2026, "calendar_attended", "test_season");

        assertEquals(1L, tallies.get(AlmanacKeys.season("test_season", 2026, AlmanacKeys.ATTENDED)));
        assertEquals(1L, tallies.get(AlmanacKeys.lifetime("test_season", AlmanacKeys.ATTENDED)),
                "a second sign of life in the same season adds nothing");

        AlmanacCounter.count(tallies, id -> SeasonState.liveIn(2027), "CALENDAR_ATTENDED", "Test_Season");
        assertEquals(2L, tallies.get(AlmanacKeys.lifetime("test_season", AlmanacKeys.ATTENDED)));
    }

    @Test
    void eachRunOfAYearIsOneMoreRunAndTheYearIsOneSeason() {
        CounterMap tallies = new CounterMap();
        // The calendar fires one attendance per player per run: two moments in one year are its two runs.
        AlmanacCounter.count(tallies, LIVE_2026, "CALENDAR_ATTENDED", "Test_Season");
        AlmanacCounter.count(tallies, LIVE_2026, "CALENDAR_ATTENDED", "Test_Season");
        assertEquals(2L, tallies.get(AlmanacKeys.season("test_season", 2026, AlmanacKeys.RUNS)));
        assertEquals(1L, tallies.get(AlmanacKeys.season("test_season", 2026, AlmanacKeys.ATTENDED)), "one season-year");
        assertEquals(1L, tallies.get(AlmanacKeys.lifetime("test_season", AlmanacKeys.ATTENDED)), "seasons count years");
        assertEquals(2L, AlmanacKeys.runsAttended(tallies, "Test_Season", 2026));

        CounterMap before = new CounterMap();
        before.add(AlmanacKeys.season("test_season", 2025, AlmanacKeys.ATTENDED), 1L);
        assertEquals(1L, AlmanacKeys.runsAttended(before, "test_season", 2025),
                "a year attended before runs were counted had one run");
        assertEquals(0L, AlmanacKeys.runsAttended(before, "test_season", 2024));
    }

    // A season-year attended under a build that kept no run count holds its $attended mark alone, which reads as one
    // run: attended again, that run is the second, never the first.
    @Test
    void aYearAttendedBeforeRunsWereCountedReadsTwoRunsOnceAttendedAgain() {
        CounterMap tallies = new CounterMap();
        tallies.add(AlmanacKeys.season("test_season", 2026, AlmanacKeys.ATTENDED), 1L);
        tallies.add(AlmanacKeys.lifetime("test_season", AlmanacKeys.ATTENDED), 1L);

        AlmanacCounter.count(tallies, LIVE_2026, "CALENDAR_ATTENDED", "Test_Season");

        assertEquals(2L, AlmanacKeys.runsAttended(tallies, "test_season", 2026), "the run before and this one");
        assertEquals(2L, tallies.get(AlmanacKeys.season("test_season", 2026, AlmanacKeys.RUNS)));
        assertEquals(1L, tallies.get(AlmanacKeys.lifetime("test_season", AlmanacKeys.ATTENDED)), "still one season");

        AlmanacCounter.count(tallies, LIVE_2026, "CALENDAR_ATTENDED", "Test_Season");
        assertEquals(3L, AlmanacKeys.runsAttended(tallies, "test_season", 2026), "seeded once, then one a run");
    }

    @Test
    void attendanceBetweenSeasonsMarksNothing() {
        CounterMap tallies = new CounterMap();

        AlmanacCounter.count(tallies, BETWEEN, "CALENDAR_ATTENDED", "Test_Season");

        assertTrue(tallies.isEmpty());
    }
}
