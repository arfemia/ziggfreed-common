package com.ziggfreed.common.almanac.page;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.ziggfreed.common.almanac.AlmanacCalendar.SeasonState;
import com.ziggfreed.common.almanac.AlmanacFixtures;
import com.ziggfreed.common.almanac.AlmanacSwitch;
import com.ziggfreed.common.almanac.asset.AlmanacEntryConfig;
import com.ziggfreed.common.almanac.page.AlmanacMenuTab.Knobs;
import com.ziggfreed.common.ui.menu.MenuEntry;
import com.ziggfreed.common.ui.menu.MenuSlot;
import com.ziggfreed.common.ui.menu.MenuSubline;
import com.ziggfreed.common.ui.route.DestinationContext;

/**
 * The Almanac tab's one rule: the Almanac is on and lists a season, the owner shows the tab, and, when the
 * owner shows it only while a season runs, one is running. By default it shows whenever a season is listed.
 * The tab sits above Quests and Achievements (M484), and while a season runs its second line names that
 * season with the season's own picture (M485).
 */
class AlmanacMenuTabTest {

    /** A season page with a name and no picture. */
    private static final String NO_PICTURE = """
            { "Text": { "TitleKey": "almanac.test.title" }, "Order": 10 }
            """;

    /** A season page with a picture and no name. */
    private static final String NO_NAME = """
            { "Icon": "Test_Icon", "Order": 10 }
            """;

    /** A second named and pictured season, listed after the fixture's. */
    private static final String LATER = """
            { "Text": { "TitleKey": "almanac.later.title" }, "Icon": "Later_Icon", "Order": 20 }
            """;

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

    /** M484: the Almanac tab sits above the Quests and Achievements tabs on the rail. */
    @Test
    void theAlmanacTabSitsAboveQuestsAndAchievements() {
        assertTrue(MenuSlot.ALMANAC.ordinal() < MenuSlot.QUESTS.ordinal(), "above Quests");
        assertTrue(MenuSlot.ALMANAC.ordinal() < MenuSlot.ACHIEVEMENTS.ordinal(), "above Achievements");
        assertEquals(0, MenuSlot.ALMANAC.ordinal(), "the first of the library's tabs");
    }

    /** M485: while a season runs, the tab's second line names it, with the season's own picture. */
    @Test
    void theSecondLineNamesTheSeasonOnNowWithItsPicture() throws Exception {
        AlmanacEntryConfig.getInstance().mergePackLayer(Map.of("test_season",
                AlmanacFixtures.page(AlmanacFixtures.SEASON_PAGE, "Test_Season")));

        MenuSubline line = AlmanacMenuTab.subline(id -> SeasonState.liveIn(2026));
        assertNotNull(line, "a season is on, so the tab names it");
        assertEquals("almanac.test.title", line.label().getFormattedMessage().messageId,
                "the season's own name, a key the client resolves");
        assertNotNull(line.icon());
        assertEquals("Test_Icon", line.icon().itemId(), "the season's own picture");
    }

    @Test
    void theSecondLineNamesTheFirstSeasonOnNowInListOrder() throws Exception {
        AlmanacEntryConfig.getInstance().mergePackLayer(Map.of(
                "test_season", AlmanacFixtures.page(AlmanacFixtures.SEASON_PAGE, "Test_Season"),
                "later_season", AlmanacFixtures.page(LATER, "Later_Season")));

        MenuSubline both = AlmanacMenuTab.subline(id -> SeasonState.liveIn(2026));
        assertNotNull(both);
        assertEquals("almanac.test.title", both.label().getFormattedMessage().messageId,
                "two seasons on: the one the Almanac lists first, the one it opens on");
        MenuSubline later = AlmanacMenuTab.subline(id -> "later_season".equals(id) ? SeasonState.liveIn(2026)
                : SeasonState.BETWEEN);
        assertNotNull(later);
        assertEquals("almanac.later.title", later.label().getFormattedMessage().messageId,
                "the season on now, wherever it is listed");
        assertEquals("Later_Icon", later.icon().itemId());
    }

    @Test
    void noSecondLineWhileNoSeasonRunsOrTheAlmanacIsOff() throws Exception {
        AlmanacEntryConfig.getInstance().mergePackLayer(Map.of("test_season",
                AlmanacFixtures.page(AlmanacFixtures.SEASON_PAGE, "Test_Season")));

        assertNull(AlmanacMenuTab.subline(id -> SeasonState.BETWEEN), "between seasons: the tab is one line");
        assertNull(AlmanacMenuTab.subline(id -> null), "a season the calendar does not answer for is never on");
        AlmanacSwitch.set(false);
        assertNull(AlmanacMenuTab.subline(id -> SeasonState.liveIn(2026)), "switched off means absent");
    }

    @Test
    void noSecondLineForASeasonWithNoPictureOrNoName() throws Exception {
        AlmanacEntryConfig.getInstance().mergePackLayer(Map.of("test_season",
                AlmanacFixtures.page(NO_PICTURE, "Test_Season")));
        assertNull(AlmanacMenuTab.subline(id -> SeasonState.liveIn(2026)), "a season with no picture draws no line");

        AlmanacEntryConfig.getInstance().mergePackLayer(Map.of("test_season",
                AlmanacFixtures.page(NO_NAME, "Test_Season")));
        assertNull(AlmanacMenuTab.subline(id -> SeasonState.liveIn(2026)),
                "a season with no name draws no line, never its raw id");
    }

    @Test
    void theTabAsksForItsSecondLineOnEveryPaint() {
        MenuEntry entry = AlmanacMenuTab.entry();
        assertNotNull(entry.subline(), "the Almanac tab carries its second line's rule");
        assertNull(entry.subline().apply(new DestinationContext(null, null, null, null, null, null)),
                "with no calendar on this server, no season runs and the tab is one line");
    }
}
