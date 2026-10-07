package com.ziggfreed.common.calendar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.ziggfreed.common.calendar.asset.CalendarEventAsset;
import com.ziggfreed.common.calendar.tick.CalendarTick;
import com.ziggfreed.common.feedback.EventTitles;
import com.ziggfreed.common.occurrence.Occurrence;

/**
 * One credit naming a player for several runs at once queues their start banners: the first shows at once and
 * each later one a gap after the one before, so the second never overwrites the first before it is read. One
 * tick ending several runs queues their end banners the same way.
 */
class CalendarHeraldQueueTest {

    private static final long GAP = CalendarHerald.START_GAP_MS;

    private static final Map<String, CalendarEventAsset> EVENTS = Map.of(
            "hallows_eve", withStart("Hallows_Eve", "eve.start"),
            "harvest_moon", withStart("Harvest_Moon", "moon.start"),
            "winter_fair", withStart("Winter_Fair", "fair.start"),
            "quiet_day", CalendarFixtures.event("Quiet_Day", CalendarFixtures.HALLOWS_EVE),
            "still_night", CalendarFixtures.event("Still_Night", CalendarFixtures.HARVEST_MOON));

    private static CalendarEventAsset withStart(String id, String titleKey) {
        return CalendarFixtures.event(id, "{ \"Window\": { \"Start\": \"10-01\", \"End\": \"11-03\" },"
                + " \"FirstYear\": 2026, \"Herald\": { \"Start\": { \"TitleKey\": \"" + titleKey + "\" } } }");
    }

    private static List<CalendarHerald.QueuedBanner> queue(String... eventIds) {
        return CalendarHerald.startQueue(List.of(eventIds), EVENTS::get);
    }

    private static List<String> ids(List<CalendarHerald.QueuedBanner> queue) {
        return queue.stream().map(CalendarHerald.QueuedBanner::eventId).toList();
    }

    private static List<Long> delays(List<CalendarHerald.QueuedBanner> queue) {
        return queue.stream().map(CalendarHerald.QueuedBanner::delayMs).toList();
    }

    private static List<String> titles(List<CalendarHerald.QueuedBanner> queue) {
        return queue.stream().map(start -> start.line().titleKey()).toList();
    }

    @Test
    void oneFreshRunShowsItsBannerAtOnce() {
        List<CalendarHerald.QueuedBanner> queue = queue("hallows_eve");
        assertEquals(List.of("hallows_eve"), ids(queue));
        assertEquals(List.of(0L), delays(queue), "a lone banner shows as it always has, at once");
        assertEquals(List.of("eve.start"), titles(queue));
    }

    @Test
    void twoFreshRunsShowTheSecondBannerAGapAfterTheFirst() {
        List<CalendarHerald.QueuedBanner> queue = queue("hallows_eve", "harvest_moon");
        assertEquals(List.of("hallows_eve", "harvest_moon"), ids(queue));
        assertEquals(List.of(0L, GAP), delays(queue), "the second waits instead of overwriting the first");
    }

    @Test
    void threeFreshRunsShowEachBannerAGapAfterTheOneBefore() {
        List<CalendarHerald.QueuedBanner> queue = queue("hallows_eve", "harvest_moon", "winter_fair");
        assertEquals(List.of("hallows_eve", "harvest_moon", "winter_fair"), ids(queue));
        assertEquals(List.of(0L, GAP, 2 * GAP), delays(queue));
    }

    @Test
    void aRunWhoseEventAuthorsNoStartLineTakesNoSlot() {
        List<CalendarHerald.QueuedBanner> queue = queue("quiet_day", "hallows_eve", "still_night", "gone",
                "harvest_moon");
        assertEquals(List.of("hallows_eve", "harvest_moon"), ids(queue),
                "a run with no start line, or an event no longer loaded, owes no banner");
        assertEquals(List.of(0L, GAP), delays(queue), "a banner is never held back for a run that shows none");
    }

    @Test
    void theBannersKeepTheCreditsOrder() {
        List<CalendarHerald.QueuedBanner> queue = queue("winter_fair", "hallows_eve", "harvest_moon");
        assertEquals(List.of("winter_fair", "hallows_eve", "harvest_moon"), ids(queue));
        assertEquals(List.of("fair.start", "eve.start", "moon.start"), titles(queue),
                "each queued banner is its own event's start line");
    }

    @Test
    void theGapLetsABannerFadeInAndHoldBeforeTheNextReplacesIt() {
        long fadeInAndHoldMs = Math.round((EventTitles.DEFAULT_FADE_IN + EventTitles.DEFAULT_DURATION) * 1000.0);
        assertTrue(GAP >= fadeInAndHoldMs, "the next banner arrives only once the one before has been up its time");
    }

    @Test
    void nothingCreditedOwesNoBanner() {
        assertTrue(queue().isEmpty());
    }

    private static CalendarEventAsset withEnd(String id, String titleKey) {
        return CalendarFixtures.event(id, "{ \"Window\": { \"Start\": \"10-01\", \"End\": \"11-03\" },"
                + " \"FirstYear\": 2026, \"Herald\": { \"End\": { \"TitleKey\": \"" + titleKey + "\" } } }");
    }

    private static final Map<String, CalendarEventAsset> ENDING = Map.of(
            "hallows_eve", withEnd("Hallows_Eve", "eve.end"),
            "harvest_moon", withEnd("Harvest_Moon", "moon.end"),
            "winter_fair", withEnd("Winter_Fair", "fair.end"),
            "quiet_day", CalendarFixtures.event("Quiet_Day", CalendarFixtures.HALLOWS_EVE));

    private static CalendarTick.Ended ended(String eventId, boolean switchedOff) {
        return new CalendarTick.Ended(new Occurrence(eventId, 2026, 1L, 2L), switchedOff);
    }

    @Test
    void runsEndingOnOneTickQueueTheirEndBannersAGapApart() {
        CalendarTick tick = new CalendarTick(2L, false, Set.of(), List.of(), List.of(
                ended("hallows_eve", false), ended("harvest_moon", false), ended("winter_fair", false)));
        List<CalendarHerald.QueuedBanner> queue = CalendarHerald.endQueue(tick, ENDING::get);
        assertEquals(List.of("hallows_eve", "harvest_moon", "winter_fair"), ids(queue));
        assertEquals(List.of(0L, GAP, 2 * GAP), delays(queue), "seasons ending together never overwrite each other");
        assertEquals(List.of("eve.end", "moon.end", "fair.end"), titles(queue));
    }

    @Test
    void aSwitchOffAndARunWithNoEndLineTakeNoSlot() {
        CalendarTick tick = new CalendarTick(2L, false, Set.of(), List.of(), List.of(
                ended("winter_fair", true), ended("quiet_day", false), ended("harvest_moon", false)));
        List<CalendarHerald.QueuedBanner> queue = CalendarHerald.endQueue(tick, ENDING::get);
        assertEquals(List.of("harvest_moon"), ids(queue), "off means absent, and no line means no banner");
        assertEquals(List.of(0L), delays(queue), "a banner is never held back for one that shows nothing");
        assertEquals(List.of("moon.end"), CalendarHerald.endLines(tick, ENDING::get).stream()
                .map(CalendarEventAsset.HeraldLine::titleKey).toList(), "endLines reads the same queue");
    }
}
