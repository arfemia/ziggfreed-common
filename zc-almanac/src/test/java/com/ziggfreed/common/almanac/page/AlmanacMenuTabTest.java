package com.ziggfreed.common.almanac.page;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.ziggfreed.common.almanac.AlmanacCalendar.SeasonState;
import com.ziggfreed.common.almanac.AlmanacFixtures;
import com.ziggfreed.common.almanac.AlmanacSwitch;
import com.ziggfreed.common.almanac.asset.AlmanacEntryConfig;
import com.ziggfreed.common.almanac.page.AlmanacMenuTab.Knobs;
import com.ziggfreed.common.ui.menu.MenuSlot;

/**
 * The Almanac tab's one rule: the Almanac is on and lists a season, the owner shows the tab, and, when the
 * owner shows it only while a season runs, one is running. By default it shows whenever a season is listed.
 */
class AlmanacMenuTabTest {

    @AfterEach
    void clear() {
        AlmanacSwitch.resetForTests();
        AlmanacMenuTab.resetForTests();
        AlmanacEntryConfig.getInstance().mergePackLayer(Map.of());
    }

    @Test
    void theRuleReadsItsThreeInputs() {
        assertEquals(new Knobs(true, false), Knobs.DEFAULTS, "shown whenever a season is listed");
        assertTrue(AlmanacMenuTab.visible(true, Knobs.DEFAULTS, false));
        assertFalse(AlmanacMenuTab.visible(false, Knobs.DEFAULTS, true), "no Almanac to offer");
        assertFalse(AlmanacMenuTab.visible(true, new Knobs(false, false), true), "the owner hid it");
        assertFalse(AlmanacMenuTab.visible(true, new Knobs(true, true), false), "only while a season runs, and none does");
        assertTrue(AlmanacMenuTab.visible(true, new Knobs(true, true), true));
    }

    @Test
    void theMenuTabAsksTheSwitchTheSeasonsAndTheKnobs() throws Exception {
        AlmanacEntryConfig.getInstance().mergePackLayer(Map.of("test_season",
                AlmanacFixtures.page(AlmanacFixtures.SEASON_PAGE, "Test_Season")));

        assertTrue(AlmanacPages.menuTabVisible(id -> SeasonState.BETWEEN));
        AlmanacMenuTab.set(new Knobs(true, true));
        assertFalse(AlmanacPages.menuTabVisible(id -> SeasonState.BETWEEN));
        assertTrue(AlmanacPages.menuTabVisible(id -> SeasonState.liveIn(2026)));
        AlmanacSwitch.set(false);
        assertFalse(AlmanacPages.menuTabVisible(id -> SeasonState.liveIn(2026)), "switched off means absent");
    }

    @Test
    void theEntryFillsTheAlmanacSlot() {
        assertEquals(MenuSlot.ALMANAC.id(), AlmanacMenuTab.entry().id());
    }
}
