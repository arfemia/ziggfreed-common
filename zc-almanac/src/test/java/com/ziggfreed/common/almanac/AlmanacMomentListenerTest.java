package com.ziggfreed.common.almanac;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

/** The listener's one decision before it touches a player: is there anything to count at all? */
class AlmanacMomentListenerTest {

    @Test
    void itCountsOnlyWhileSwitchedOnAndOnlyKindsAPageOrTheCalendarNames() throws Exception {
        AlmanacIndex index = AlmanacIndex.of(Map.of("test_season",
                AlmanacFixtures.page(AlmanacFixtures.SEASON_PAGE, "Test_Season")));

        assertTrue(AlmanacMomentListener.shouldCount(true, "USE_ITEM", index));
        assertTrue(AlmanacMomentListener.shouldCount(true, "CALENDAR_ATTENDED", index), "attendance always counts");
        assertFalse(AlmanacMomentListener.shouldCount(true, "BREAK_BLOCK", index),
                "a kind no page names costs one lookup and nothing else");
        assertFalse(AlmanacMomentListener.shouldCount(false, "USE_ITEM", index), "switched off means no counting");
        assertFalse(AlmanacMomentListener.shouldCount(false, "CALENDAR_ATTENDED", index));
    }
}
