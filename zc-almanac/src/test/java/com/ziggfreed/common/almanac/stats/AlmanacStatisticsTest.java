package com.ziggfreed.common.almanac.stats;

import static com.ziggfreed.common.almanac.FixedCalendar.TEST_SEASON;
import static com.ziggfreed.common.almanac.FixedCalendar.betweenAfter;
import static com.ziggfreed.common.almanac.FixedCalendar.liveIn;
import static com.ziggfreed.common.almanac.FixedCalendar.noon;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.protocol.FormattedMessage;
import com.hypixel.hytale.protocol.LongParamValue;
import com.hypixel.hytale.server.core.Message;
import com.ziggfreed.common.almanac.AlmanacFixtures;
import com.ziggfreed.common.almanac.AlmanacKeys;
import com.ziggfreed.common.almanac.AlmanacSwitch;
import com.ziggfreed.common.almanac.AlmanacText;
import com.ziggfreed.common.almanac.FixedCalendar;
import com.ziggfreed.common.almanac.asset.AlmanacEntryAsset;
import com.ziggfreed.common.almanac.page.AlmanacDestinations;
import com.ziggfreed.common.counter.CounterMap;
import com.ziggfreed.common.ui.kit.ActionSlot;
import com.ziggfreed.common.ui.kit.DetailAction;
import com.ziggfreed.common.ui.kit.DetailBlock;
import com.ziggfreed.common.ui.kit.DetailView;
import com.ziggfreed.common.ui.kit.LedgerRow;
import com.ziggfreed.common.ui.kit.LedgerSection;
import com.ziggfreed.common.ui.kit.Tone;

/**
 * The book's Seasons statistics, as the Almanac contributes them through {@code LedgerContributions}: one
 * Seasons section with a row per listed season (on now first) saying how many of its seasons the player
 * took part in, and a row's page with the season's every-season tallies and a way into the Almanac on it.
 */
class AlmanacStatisticsTest {

    private static final long NOW = noon("2026-10-07");

    @AfterEach
    void reset() {
        AlmanacSwitch.resetForTests();
    }

    private static Map<String, AlmanacEntryAsset> pages() throws Exception {
        return Map.of(TEST_SEASON, AlmanacFixtures.page(AlmanacFixtures.SEASON_PAGE, "Test_Season"),
                "winter", AlmanacFixtures.page("{ \"Order\": 5, \"Icon\": \"Test_Snow\" }", "Winter"));
    }

    private static FixedCalendar calendar() {
        return new FixedCalendar()
                .season(TEST_SEASON, liveIn(2026, 2025))
                .season("winter", betweenAfter(2026, 2025));
    }

    private static AlmanacStatistics source() throws Exception {
        Map<String, AlmanacEntryAsset> pages = pages();
        return new AlmanacStatistics(() -> pages, calendar());
    }

    private static CounterMap tallies() {
        CounterMap tallies = new CounterMap();
        tallies.add(AlmanacKeys.lifetime(TEST_SEASON, AlmanacKeys.ATTENDED), 2L);
        tallies.add(AlmanacKeys.lifetime(TEST_SEASON, "bombs_thrown"), 12L);
        tallies.add(AlmanacKeys.season(TEST_SEASON, 2026, "bombs_thrown"), 5L);
        return tallies;
    }

    private static String key(Message message) {
        return message.getFormattedMessage().messageId;
    }

    private static long number(Message message, String param) {
        FormattedMessage formatted = message.getFormattedMessage();
        return ((LongParamValue) formatted.params.get(param)).value;
    }

    @Test
    void theSourceSitsOnTheStatisticsSurfaceAfterAConsumersOwn() throws Exception {
        AlmanacStatistics source = source();

        assertEquals("ziggfreedcommon:almanac", source.id());
        assertEquals(AlmanacStatistics.ID, source.id());
        assertEquals(900, source.order());
    }

    @Test
    void oneSeasonsSectionWithARowPerSeasonOnNowFirst() throws Exception {
        List<LedgerSection> sections = source().sections(tallies());

        assertEquals(1, sections.size());
        LedgerSection seasons = sections.get(0);
        assertEquals(AlmanacStatistics.SECTION, seasons.id());
        assertEquals(AlmanacText.PREFIX + "stats.section", key(seasons.label()));
        assertTrue(seasons.openByDefault());
        assertEquals(List.of(TEST_SEASON, "winter"), seasons.rows().stream().map(LedgerRow::id).toList(),
                "the season on now leads, though winter's Order is lower");

        LedgerRow live = seasons.rows().get(0);
        assertEquals("almanac.test.title", key(live.title()));
        assertEquals(AlmanacText.PREFIX + "stats.row.meta", key(live.meta()));
        assertEquals(2L, number(live.meta(), "0"), "seasons taken part in, a typed number");
        assertEquals("Test_Icon", live.picture().itemId());
        assertEquals(Tone.LIVE, live.tone());
        assertEquals(AlmanacText.PREFIX + "status.live", key(live.state()));

        LedgerRow resting = seasons.rows().get(1);
        assertEquals(0L, number(resting.meta(), "0"), "a season never taken part in still lists, at 0");
        assertEquals(Tone.NEUTRAL, resting.tone());
        assertNull(resting.state());
        assertEquals("Test_Snow", resting.picture().itemId());
    }

    @Test
    void nothingWhileTheAlmanacIsOffOrListsNoSeason() throws Exception {
        AlmanacSwitch.set(false);
        assertTrue(source().sections(tallies()).isEmpty(), "off means absent: the book hides the section");
        assertNull(source().page(TEST_SEASON, tallies(), NOW));
        AlmanacSwitch.set(true);

        assertTrue(new AlmanacStatistics(Map::of, calendar()).sections(tallies()).isEmpty());
        Map<String, AlmanacEntryAsset> pages = pages();
        assertTrue(new AlmanacStatistics(() -> pages, new FixedCalendar()).sections(tallies()).isEmpty(),
                "a season the calendar does not answer for is not listed");
    }

    @Test
    void aRowsPageListsTheEverySeasonTalliesAndOpensTheAlmanacOnTheSeason() throws Exception {
        DetailView page = source().page(TEST_SEASON, tallies(), NOW);

        assertNotNull(page);
        assertEquals("almanac.test.title", key(page.title()));
        assertEquals("Test_Icon", page.picture().itemId());
        assertEquals(AlmanacText.PREFIX + "chip.live", key(page.meta()), "when it runs, as the Almanac says it");
        assertEquals(AlmanacText.PREFIX + "window", key(page.subMeta()));
        assertEquals("almanac.test.flavor", key(page.lead()));

        assertEquals(1, page.blocks().size());
        DetailBlock block = page.blocks().get(0);
        assertEquals(AlmanacText.PREFIX + "scope.every", key(block.label()));
        assertEquals(AlmanacText.PREFIX + "scope.taken_part_count", key(block.meta()));
        assertEquals(2L, number(block.meta(), "0"));
        assertEquals(2, block.lines().size());
        assertEquals("almanac.test.bombs", key(block.lines().get(0).text()), "by Order, then name");
        assertEquals("ghouls", block.lines().get(1).text().getFormattedMessage().rawText,
                "a stat with no name reads as its id");
        assertEquals(12L, number(block.lines().get(0).count(), "0"), "every season's count, typed");
        assertEquals(0L, number(block.lines().get(1).count(), "0"), "a tally nothing counted reads 0");
        assertEquals("Test_Bomb", block.lines().get(0).picture().itemId());

        assertEquals(1, page.actions().size());
        DetailAction open = page.actions().get(0);
        assertEquals(ActionSlot.PRIMARY, open.slot());
        assertEquals(AlmanacText.PREFIX + "stats.open", key(open.label()));
        assertTrue(open.enabled());
        AlmanacDestinations.Almanac where = assertInstanceOf(AlmanacDestinations.Almanac.class, open.destination());
        assertEquals(TEST_SEASON, where.getEvent());
    }

    @Test
    void aRowOpensItsPageUnderAnyCasingAndARowThatIsGoneHasNone() throws Exception {
        assertNotNull(source().page("Test_Season", tallies(), NOW));
        assertNull(source().page("nope", tallies(), NOW));

        DetailView resting = source().page("winter", new CounterMap(), NOW);
        assertNotNull(resting);
        assertEquals(AlmanacText.PREFIX + "chip.returns_on", key(resting.meta()), "between seasons, it says when");
        assertTrue(resting.blocks().isEmpty(), "a season with no tallies to read has no block");
        assertNull(resting.lead());
        assertFalse(resting.actions().isEmpty(), "and still opens the Almanac");
    }
}
