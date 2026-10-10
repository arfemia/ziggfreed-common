package com.ziggfreed.common.almanac;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.ziggfreed.common.almanac.AlmanacCalendar.SeasonState;
import com.ziggfreed.common.counter.CounterMap;

/**
 * The listener's one decision before it touches a player (is there anything to count at all?), and what a
 * counted moment adds to the server's own totals beside the player's record.
 */
class AlmanacMomentListenerTest {

    private static final AlmanacCalendar LIVE_2026 =
            id -> "test_season".equals(id) ? SeasonState.liveIn(2026) : null;

    private static ServerTallies server() {
        ServerTallies server = new ServerTallies((task, delayMs) -> task.run());
        server.init(null);
        return server;
    }

    @Test
    void itCountsOnlyWhileSwitchedOnAndOnlyTheCalendarsAttendance() {
        assertTrue(AlmanacMomentListener.shouldCount(true, "CALENDAR_ATTENDED"), "attendance always counts");
        assertTrue(AlmanacMomentListener.shouldCount(true, "calendar_attended"));
        assertFalse(AlmanacMomentListener.shouldCount(true, "USE_ITEM"),
                "a page's stat line counts in the player stat store, never here");
        assertFalse(AlmanacMomentListener.shouldCount(true, "BREAK_BLOCK"),
                "any other kind costs one comparison and nothing else");
        assertFalse(AlmanacMomentListener.shouldCount(false, "CALENDAR_ATTENDED"), "switched off means no counting");
    }

    @Test
    void aCountedAttendanceAddsToTheServersTotalsBesideThePlayersOwn() {
        ServerTallies server = server();
        CounterMap alice = new CounterMap();
        CounterMap bob = new CounterMap();

        AlmanacMomentListener.count(alice, server, LIVE_2026, AlmanacCounter.ATTENDED_KIND, "Test_Season");
        AlmanacMomentListener.count(alice, server, LIVE_2026, AlmanacCounter.ATTENDED_KIND, "Test_Season");
        AlmanacMomentListener.count(bob, server, LIVE_2026, AlmanacCounter.ATTENDED_KIND, "Test_Season");

        String runs = AlmanacKeys.season("test_season", 2026, AlmanacKeys.RUNS);
        assertEquals(2L, alice.get(runs), "the player's own record: two runs");
        assertEquals(3L, server.get(runs), "everyone's runs this season-year");
    }

    @Test
    void attendanceAddsEachPlayerOnceToTheServer() {
        ServerTallies server = server();
        CounterMap alice = new CounterMap();
        CounterMap bob = new CounterMap();

        AlmanacMomentListener.count(alice, server, LIVE_2026, AlmanacCounter.ATTENDED_KIND, "Test_Season");
        AlmanacMomentListener.count(alice, server, LIVE_2026, AlmanacCounter.ATTENDED_KIND, "Test_Season");
        AlmanacMomentListener.count(bob, server, LIVE_2026, AlmanacCounter.ATTENDED_KIND, "Test_Season");

        assertEquals(2L, server.get(AlmanacKeys.season("test_season", 2026, AlmanacKeys.ATTENDED)),
                "two players took part this season");
        assertEquals(2L, server.get(AlmanacKeys.lifetime("test_season", AlmanacKeys.ATTENDED)),
                "and two player-seasons in all");
    }

    @Test
    void aMomentThatCountsNothingAddsNothingToTheServer() {
        ServerTallies server = server();

        AlmanacMomentListener.count(new CounterMap(), server, id -> null, "USE_ITEM", "Test_Bomb_Rare");

        assertEquals(0L, server.get(AlmanacKeys.lifetime("test_season", "bombs_thrown")),
                "an absent season counts nothing, for the player or the server");
    }
}
