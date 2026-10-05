package com.ziggfreed.common.calendar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.ziggfreed.common.calendar.asset.CalendarEventAsset;
import com.ziggfreed.common.feedback.EventTitles;

/**
 * One credit naming a player for several runs at once queues their start banners: the first shows at once and
 * each later one a gap after the one before, so the second never overwrites the first before it is read.
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

    private static List<CalendarHerald.QueuedStart> queue(String... eventIds) {
        return CalendarHerald.startQueue(List.of(eventIds), EVENTS::get);
    }

    private static List<String> ids(List<CalendarHerald.QueuedStart> queue) {
        return queue.stream().map(CalendarHerald.QueuedStart::eventId).toList();
    }

    private static List<Long> delays(List<CalendarHerald.QueuedStart> queue) {
        return queue.stream().map(CalendarHerald.QueuedStart::delayMs).toList();
    }

    private static List<String> titles(List<CalendarHerald.QueuedStart> queue) {
        return queue.stream().map(start -> start.line().titleKey()).toList();
    }

    @Test
    void oneFreshRunShowsItsBannerAtOnce() {
        List<CalendarHerald.QueuedStart> queue = queue("hallows_eve");
        assertEquals(List.of("hallows_eve"), ids(queue));
        assertEquals(List.of(0L), delays(queue), "a lone banner shows as it always has, at once");
        assertEquals(List.of("eve.start"), titles(queue));
    }

    @Test
    void twoFreshRunsShowTheSecondBannerAGapAfterTheFirst() {
        List<CalendarHerald.QueuedStart> queue = queue("hallows_eve", "harvest_moon");
        assertEquals(List.of("hallows_eve", "harvest_moon"), ids(queue));
        assertEquals(List.of(0L, GAP), delays(queue), "the second waits instead of overwriting the first");
    }

    @Test
    void threeFreshRunsShowEachBannerAGapAfterTheOneBefore() {
        List<CalendarHerald.QueuedStart> queue = queue("hallows_eve", "harvest_moon", "winter_fair");
        assertEquals(List.of("hallows_eve", "harvest_moon", "winter_fair"), ids(queue));
        assertEquals(List.of(0L, GAP, 2 * GAP), delays(queue));
    }

    @Test
    void aRunWhoseEventAuthorsNoStartLineTakesNoSlot() {
        List<CalendarHerald.QueuedStart> queue = queue("quiet_day", "hallows_eve", "still_night", "gone",
                "harvest_moon");
        assertEquals(List.of("hallows_eve", "harvest_moon"), ids(queue),
                "a run with no start line, or an event no longer loaded, owes no banner");
        assertEquals(List.of(0L, GAP), delays(queue), "a banner is never held back for a run that shows none");
    }

    @Test
    void theBannersKeepTheCreditsOrder() {
        List<CalendarHerald.QueuedStart> queue = queue("winter_fair", "hallows_eve", "harvest_moon");
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
}
