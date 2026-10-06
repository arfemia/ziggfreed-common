package com.ziggfreed.common.almanac;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

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

    private static AlmanacIndex index() throws Exception {
        return AlmanacIndex.of(Map.of("test_season", AlmanacFixtures.page(AlmanacFixtures.SEASON_PAGE, "Test_Season")));
    }

    private static ServerTallies server() {
        ServerTallies server = new ServerTallies((task, delayMs) -> task.run());
        server.init(null);
        return server;
    }

    @Test
    void itCountsOnlyWhileSwitchedOnAndOnlyKindsAPageOrTheCalendarNames() throws Exception {
        AlmanacIndex index = index();

        assertTrue(AlmanacMomentListener.shouldCount(true, "USE_ITEM", index));
        assertTrue(AlmanacMomentListener.shouldCount(true, "CALENDAR_ATTENDED", index), "attendance always counts");
        assertFalse(AlmanacMomentListener.shouldCount(true, "BREAK_BLOCK", index),
                "a kind no page names costs one lookup and nothing else");
        assertFalse(AlmanacMomentListener.shouldCount(false, "USE_ITEM", index), "switched off means no counting");
        assertFalse(AlmanacMomentListener.shouldCount(false, "CALENDAR_ATTENDED", index));
    }

    @Test
    void aCountedMomentAddsToTheServersTotalsBesideThePlayersOwn() throws Exception {
        ServerTallies server = server();
        CounterMap alice = new CounterMap();
        CounterMap bob = new CounterMap();

        AlmanacMomentListener.count(alice, server, index(), LIVE_2026, "USE_ITEM", "Test_Bomb_Rare", "Throw", 2L, null);
        AlmanacMomentListener.count(bob, server, index(), LIVE_2026, "USE_ITEM", "Test_Bomb_Rare", "Throw", 3L, null);

        assertEquals(2L, alice.get(AlmanacKeys.lifetime("test_season", "bombs_thrown")), "the player's own record");
        assertEquals(5L, server.get(AlmanacKeys.lifetime("test_season", "bombs_thrown")), "everyone's, every season");
        assertEquals(5L, server.get(AlmanacKeys.season("test_season", 2026, "bombs_thrown")), "everyone's, this season");
    }

    @Test
    void attendanceAddsEachPlayerOnceToTheServer() throws Exception {
        ServerTallies server = server();
        CounterMap alice = new CounterMap();
        CounterMap bob = new CounterMap();

        AlmanacMomentListener.count(alice, server, index(), LIVE_2026, AlmanacCounter.ATTENDED_KIND, "Test_Season",
                null, 1L, null);
        AlmanacMomentListener.count(alice, server, index(), LIVE_2026, AlmanacCounter.ATTENDED_KIND, "Test_Season",
                null, 1L, null);
        AlmanacMomentListener.count(bob, server, index(), LIVE_2026, AlmanacCounter.ATTENDED_KIND, "Test_Season",
                null, 1L, null);

        assertEquals(2L, server.get(AlmanacKeys.season("test_season", 2026, AlmanacKeys.ATTENDED)),
                "two players took part this season");
        assertEquals(2L, server.get(AlmanacKeys.lifetime("test_season", AlmanacKeys.ATTENDED)),
                "and two player-seasons in all");
    }

    @Test
    void aMomentThatCountsNothingAddsNothingToTheServer() throws Exception {
        ServerTallies server = server();

        AlmanacMomentListener.count(new CounterMap(), server, index(), id -> null, "USE_ITEM", "Test_Bomb_Rare",
                "Throw", 4L, null);

        assertEquals(0L, server.get(AlmanacKeys.lifetime("test_season", "bombs_thrown")),
                "an absent season counts nothing, for the player or the server");
    }
}
