package com.ziggfreed.common.almanac.page;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.protocol.LongParamValue;
import com.hypixel.hytale.server.core.Message;
import com.ziggfreed.common.almanac.AlmanacCalendar.SeasonState;
import com.ziggfreed.common.almanac.AlmanacFixtures;
import com.ziggfreed.common.almanac.AlmanacSwitch;
import com.ziggfreed.common.almanac.AlmanacText;
import com.ziggfreed.common.almanac.asset.AlmanacEntryConfig;

/** Whether there is an Almanac to offer, which is what a consumer's menu tile asks, and the line it reads. */
class AlmanacPagesTest {

    @AfterEach
    void clear() {
        AlmanacSwitch.resetForTests();
        AlmanacEntryConfig.getInstance().mergePackLayer(Map.of());
    }

    @Test
    void theAlmanacIsAvailableOnlyWhileSwitchedOnWithASeasonToShow() throws Exception {
        AlmanacEntryConfig.getInstance().mergePackLayer(Map.of("test_season",
                AlmanacFixtures.page(AlmanacFixtures.SEASON_PAGE, "Test_Season")));

        assertTrue(AlmanacPages.available(id -> SeasonState.BETWEEN));
        assertFalse(AlmanacPages.available(id -> null),
                "a season the calendar does not answer for is absent, and so is an Almanac with no other");

        AlmanacSwitch.set(false);
        assertFalse(AlmanacPages.available(id -> SeasonState.BETWEEN), "switched off means absent");
    }

    @Test
    void openingWithNoPlayerOrSwitchedOffDeclinesWithoutThrowing() {
        assertFalse(AlmanacPages.open(null, null, null, null));
        AlmanacSwitch.set(false);
        assertFalse(AlmanacPages.open("test_season", null, null, null));
    }

    @Test
    void theHeadlineNamesTheSeasonOnNowAndIsAbsentWhileNothingRuns() throws Exception {
        AlmanacEntryConfig.getInstance().mergePackLayer(Map.of("test_season",
                AlmanacFixtures.page(AlmanacFixtures.SEASON_PAGE, "Test_Season")));

        assertNull(AlmanacPages.headline(id -> SeasonState.BETWEEN), "between seasons: the tile keeps its own line");
        assertNull(AlmanacPages.headline(id -> null), "an absent season is never on");

        Message headline = AlmanacPages.headline(id -> SeasonState.liveIn(2026));
        assertNotNull(headline);
        assertEquals(AlmanacText.PREFIX + "headline.live", headline.getFormattedMessage().messageId);

        AlmanacSwitch.set(false);
        assertNull(AlmanacPages.headline(id -> SeasonState.liveIn(2026)), "switched off means absent");
    }

    @Test
    void theHeadlineNamesEverySeasonOnNow() throws Exception {
        AlmanacEntryConfig.getInstance().mergePackLayer(Map.of(
                "test_season", AlmanacFixtures.page(AlmanacFixtures.SEASON_PAGE, "Test_Season"),
                "second_season", AlmanacFixtures.page(
                        "{ \"Text\": { \"TitleKey\": \"almanac.second.title\" }, \"Order\": 20 }", "Second_Season"),
                "third_season", AlmanacFixtures.page(
                        "{ \"Text\": { \"TitleKey\": \"almanac.third.title\" }, \"Order\": 30 }", "Third_Season")));

        Message one = AlmanacPages.headline(id -> "second_season".equals(id) ? SeasonState.liveIn(2026)
                : SeasonState.BETWEEN);
        assertEquals(AlmanacText.PREFIX + "headline.live", one.getFormattedMessage().messageId);
        assertEquals("almanac.second.title", one.getFormattedMessage().messageParams.get("0").messageId);

        Message two = AlmanacPages.headline(id -> "third_season".equals(id) ? SeasonState.BETWEEN
                : SeasonState.liveIn(2026));
        assertEquals(AlmanacText.PREFIX + "headline.live.two", two.getFormattedMessage().messageId);
        assertEquals("almanac.test.title", two.getFormattedMessage().messageParams.get("0").messageId,
                "both seasons are named, in list order");
        assertEquals("almanac.second.title", two.getFormattedMessage().messageParams.get("1").messageId);

        Message three = AlmanacPages.headline(id -> SeasonState.liveIn(2026));
        assertEquals(AlmanacText.PREFIX + "headline.live.more", three.getFormattedMessage().messageId);
        assertEquals("almanac.test.title", three.getFormattedMessage().messageParams.get("0").messageId);
        assertEquals(2L, ((LongParamValue) three.getFormattedMessage().params.get("1")).value,
                "the rest are counted as a typed number");
    }

    @Test
    void theProductionHeadlineIsAbsentWithNoCalendar() {
        assertNull(AlmanacPages.headline());
    }
}
