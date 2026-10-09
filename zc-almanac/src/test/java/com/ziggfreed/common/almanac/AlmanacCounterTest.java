package com.ziggfreed.common.almanac;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.ziggfreed.common.almanac.AlmanacCalendar.SeasonState;
import com.ziggfreed.common.counter.CounterMap;

/** What one moment adds to a player's record: live, between seasons, absent, and attendance. */
class AlmanacCounterTest {

    private static final AlmanacCalendar LIVE_2026 =
            id -> "test_season".equals(id) ? SeasonState.liveIn(2026) : null;
    private static final AlmanacCalendar BETWEEN =
            id -> "test_season".equals(id) ? SeasonState.BETWEEN : null;
    private static final AlmanacCalendar ABSENT = id -> null;

    private static AlmanacIndex index() throws Exception {
        return AlmanacIndex.of(Map.of("test_season",
                AlmanacFixtures.page(AlmanacFixtures.SEASON_PAGE, "Test_Season")));
    }

    @Test
    void aMatchingMomentWhileTheSeasonIsOnCountsForTheSeasonAndForEverySeason() throws Exception {
        CounterMap tallies = new CounterMap();

        AlmanacCounter.count(tallies, index(), LIVE_2026, "USE_ITEM", "Test_Bomb_Rare", "Throw", 3L, null);

        assertEquals(3L, tallies.get(AlmanacKeys.lifetime("test_season", "bombs_thrown")), "a moment adds its own amount");
        assertEquals(3L, tallies.get(AlmanacKeys.season("test_season", 2026, "bombs_thrown")));
    }

    @Test
    void betweenSeasonsOnlyAYearRoundLineCountsAndOnlyForEverySeason() throws Exception {
        CounterMap tallies = new CounterMap();

        AlmanacCounter.count(tallies, index(), BETWEEN, "USE_ITEM", "Test_Bomb_Rare", "Throw", 1L, null);
        AlmanacCounter.count(tallies, index(), BETWEEN, "KILL_ENTITY", "Test_Ghoul", null, 1L, null);

        assertEquals(Set.of(AlmanacKeys.lifetime("test_season", "bombs_thrown")), tallies.keys(),
                "the live-only line waits for the season, and nothing is filed under a season between seasons");
    }

    @Test
    void aSeasonTheCalendarDoesNotAnswerForCountsNothing() throws Exception {
        CounterMap tallies = new CounterMap();

        AlmanacCounter.count(tallies, index(), ABSENT, "USE_ITEM", "Test_Bomb_Rare", "Throw", 1L, null);
        AlmanacCounter.count(tallies, index(), ABSENT, "CALENDAR_ATTENDED", "Test_Season", null, 1L, null);

        assertTrue(tallies.isEmpty(), "off means absent, even for a year-round line");
    }

    @Test
    void aMomentThatMatchesNoLineCountsNothing() throws Exception {
        CounterMap tallies = new CounterMap();

        AlmanacCounter.count(tallies, index(), LIVE_2026, "USE_ITEM", "Test_Bomb_Rare", "Crack", 1L, null);
        AlmanacCounter.count(tallies, index(), LIVE_2026, "KILL_ENTITY", "Other_Mob", null, 1L, null);
        AlmanacCounter.count(tallies, index(), LIVE_2026, "BREAK_BLOCK", "Test_Ghoul", null, 1L, null);

        assertTrue(tallies.isEmpty());
    }

    @Test
    void aMomentWithNoAmountCountsNothing() throws Exception {
        CounterMap tallies = new CounterMap();

        AlmanacCounter.count(tallies, index(), LIVE_2026, "KILL_ENTITY", "Test_Ghoul", null, 0L, null);
        AlmanacCounter.count(tallies, index(), LIVE_2026, "KILL_ENTITY", "Test_Ghoul", null, -4L, null);

        assertTrue(tallies.isEmpty(), "a tally never goes down");
    }

    @Test
    void eachSeasonKeepsItsOwnTallyAndEverySeasonSumsThem() throws Exception {
        CounterMap tallies = new CounterMap();

        AlmanacCounter.count(tallies, index(), id -> SeasonState.liveIn(2026), "KILL_ENTITY", "Test_Ghoul", null, 2L, null);
        AlmanacCounter.count(tallies, index(), id -> SeasonState.liveIn(2027), "KILL_ENTITY", "Test_Ghoul", null, 3L, null);

        assertEquals(2L, tallies.get(AlmanacKeys.season("test_season", 2026, "ghouls")));
        assertEquals(3L, tallies.get(AlmanacKeys.season("test_season", 2027, "ghouls")));
        assertEquals(5L, tallies.get(AlmanacKeys.lifetime("test_season", "ghouls")));
    }

    @Test
    void attendanceMarksASeasonOnceAndCountsTheSeasonsAttended() throws Exception {
        CounterMap tallies = new CounterMap();

        AlmanacCounter.count(tallies, index(), LIVE_2026, "CALENDAR_ATTENDED", "Test_Season", null, 1L, null);
        AlmanacCounter.count(tallies, index(), LIVE_2026, "calendar_attended", "test_season", null, 1L, null);

        assertEquals(1L, tallies.get(AlmanacKeys.season("test_season", 2026, AlmanacKeys.ATTENDED)));
        assertEquals(1L, tallies.get(AlmanacKeys.lifetime("test_season", AlmanacKeys.ATTENDED)),
                "a second sign of life in the same season adds nothing");

        AlmanacCounter.count(tallies, index(), id -> SeasonState.liveIn(2027), "CALENDAR_ATTENDED", "Test_Season", null, 1L, null);
        assertEquals(2L, tallies.get(AlmanacKeys.lifetime("test_season", AlmanacKeys.ATTENDED)));
    }

    @Test
    void eachRunOfAYearIsOneMoreRunAndTheYearIsOneSeason() throws Exception {
        CounterMap tallies = new CounterMap();
        // The calendar fires one attendance per player per run: two moments in one year are its two runs.
        AlmanacCounter.count(tallies, index(), LIVE_2026, "CALENDAR_ATTENDED", "Test_Season", null, 1L, null);
        AlmanacCounter.count(tallies, index(), LIVE_2026, "CALENDAR_ATTENDED", "Test_Season", null, 1L, null);
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
    void aYearAttendedBeforeRunsWereCountedReadsTwoRunsOnceAttendedAgain() throws Exception {
        CounterMap tallies = new CounterMap();
        tallies.add(AlmanacKeys.season("test_season", 2026, AlmanacKeys.ATTENDED), 1L);
        tallies.add(AlmanacKeys.lifetime("test_season", AlmanacKeys.ATTENDED), 1L);

        AlmanacCounter.count(tallies, index(), LIVE_2026, "CALENDAR_ATTENDED", "Test_Season", null, 1L, null);

        assertEquals(2L, AlmanacKeys.runsAttended(tallies, "test_season", 2026), "the run before and this one");
        assertEquals(2L, tallies.get(AlmanacKeys.season("test_season", 2026, AlmanacKeys.RUNS)));
        assertEquals(1L, tallies.get(AlmanacKeys.lifetime("test_season", AlmanacKeys.ATTENDED)), "still one season");

        AlmanacCounter.count(tallies, index(), LIVE_2026, "CALENDAR_ATTENDED", "Test_Season", null, 1L, null);
        assertEquals(3L, AlmanacKeys.runsAttended(tallies, "test_season", 2026), "seeded once, then one a run");
    }

    @Test
    void attendanceBetweenSeasonsMarksNothing() throws Exception {
        CounterMap tallies = new CounterMap();

        AlmanacCounter.count(tallies, index(), BETWEEN, "CALENDAR_ATTENDED", "Test_Season", null, 1L, null);

        assertTrue(tallies.isEmpty());
    }
}
