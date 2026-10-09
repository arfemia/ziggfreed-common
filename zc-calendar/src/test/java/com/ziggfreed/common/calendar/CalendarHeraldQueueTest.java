package com.ziggfreed.common.calendar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.hypixel.hytale.codec.schema.SchemaContext;
import com.hypixel.hytale.codec.schema.config.BooleanSchema;
import com.hypixel.hytale.codec.schema.config.ObjectSchema;
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

    private static CalendarEventAsset withBoth(String id, String herald) {
        return CalendarFixtures.event(id, "{ \"Window\": { \"Rule\": { \"Type\": \"Weekly\", \"Weekday\": \"Saturday\" } },"
                + " \"FirstYear\": 2026, \"Herald\": { \"Start\": { \"TitleKey\": \"" + id + ".start\" },"
                + " \"End\": { \"TitleKey\": \"" + id + ".end\" }" + herald + " } }");
    }

    private static Occurrence run(String eventId, int number) {
        return new Occurrence(eventId, 2026, number, number * 10L, number * 10L + 5L);
    }

    @Test
    void aTicksStartBannersQueueAfterItsEndBannersSoNoneOverwritesAnother() {
        Map<String, CalendarEventAsset> events = Map.of(
                "hallows_eve", withEnd("Hallows_Eve", "eve.end"),
                "harvest_moon", withEnd("Harvest_Moon", "moon.end"),
                "contest", withBoth("Contest", ""));
        CalendarTick tick = new CalendarTick(2L, false, Set.of("contest"),
                List.of(new CalendarTick.Started(run("contest", 41), false)),
                List.of(ended("hallows_eve", false), ended("harvest_moon", false)));
        long after = CalendarHerald.startsAfterMs(tick, events::get);
        assertEquals(2 * GAP, after, "the two end banners come first");
        List<CalendarHerald.QueuedBanner> starts = CalendarHerald.startQueue(List.of(run("contest", 41)), events::get, after);
        assertEquals(List.of(2 * GAP), delays(starts), "the start banner waits its turn after them");
        assertEquals(List.of(0L, GAP), delays(CalendarHerald.endQueue(tick, events::get)));
    }

    @Test
    void anEventThatSaysFirstRunOfYearShowsItsBannersOnlyForThatRun() {
        CalendarEventAsset contest = withBoth("Contest", ", \"FirstRunOfYear\": true");
        assertTrue(CalendarHerald.shows(contest, run("contest", 1)));
        assertFalse(CalendarHerald.shows(contest, run("contest", 2)), "the year's later runs come and go quietly");
        Map<String, CalendarEventAsset> events = Map.of("contest", contest);
        assertTrue(CalendarHerald.startQueue(List.of(run("contest", 2)), events::get, 0L).isEmpty());
        assertEquals(1, CalendarHerald.startQueue(List.of(run("contest", 1)), events::get, 0L).size());
        CalendarTick secondEnds = new CalendarTick(2L, false, Set.of(), List.of(),
                List.of(new CalendarTick.Ended(run("contest", 2), false)));
        assertTrue(CalendarHerald.endQueue(secondEnds, events::get).isEmpty());
        assertTrue(CalendarHerald.shows(withBoth("Contest", ""), run("contest", 7)), "unsaid, every run shows them");
    }

    @Test
    void anEventWithItsHeraldSwitchedOffShowsNoBanner() {
        CalendarEventAsset quiet = withBoth("Contest", ", \"Enabled\": false");
        assertFalse(CalendarHerald.shows(quiet, run("contest", 1)));
        assertTrue(CalendarHerald.endQueue(new CalendarTick(2L, false, Set.of(), List.of(),
                List.of(new CalendarTick.Ended(run("contest", 1), false))), Map.of("contest", quiet)::get).isEmpty());
        assertTrue(CalendarHerald.startQueue(List.of(run("contest", 1)), Map.of("contest", quiet)::get, 0L).isEmpty());
    }

    @Test
    void aRunFollowedAtOnceByItsEventsNextRunShowsNoEndBanner() {
        Occurrence february = new Occurrence("hallows_eve", 2027, 2, 1L, 2L);
        Occurrence march = new Occurrence("hallows_eve", 2027, 3, 2L, 3L);
        CalendarTick tick = new CalendarTick(2L, false, Set.of("hallows_eve"),
                List.of(new CalendarTick.Started(march, false)),
                List.of(new CalendarTick.Ended(february, false), ended("harvest_moon", false)));
        assertEquals(List.of("harvest_moon"), ids(CalendarHerald.endQueue(tick, ENDING::get)),
                "the next run's start banner speaks for it; another event's end still shows");
    }

    // A run's number is its year's rule's name for it, never its place: a weekly event numbers its runs by calendar
    // week, and January 1st 2026 is a Thursday, so a Monday contest's 2026 opens in week 2. Its first run is the
    // year's first by its dates, whatever number it carries.
    @Test
    void theYearsFirstRunIsFoundByItsDatesSinceAWeeklyYearCanBeginAtWeekTwo() {
        CalendarEventAsset mondays = CalendarFixtures.event("Contest", "{ \"Window\": { \"Rule\": { \"Type\":"
                + " \"Weekly\", \"Weekday\": \"Monday\" } }, \"FirstYear\": 2026, \"Herald\": { \"Start\": {"
                + " \"TitleKey\": \"contest.start\" }, \"FirstRunOfYear\": true } }");
        assertEquals(2, mondays.annualWindow().datedRuns(2026).get(0).number(), "no Monday of 2026 falls in week 1");
        assertTrue(CalendarHerald.shows(mondays, run("contest", 2)), "January 5th's run is the year's first");
        assertFalse(CalendarHerald.shows(mondays, run("contest", 3)));
    }

    @Test
    void anOwnerQuietsAPacksEventByItsHeraldAloneAndKeepsItsLines() {
        CalendarEventAsset quieted = CalendarFixtures.event("Contest", "{ \"Herald\": { \"Enabled\": false } }",
                withBoth("Contest", ""));
        assertEquals("Contest.start", quieted.heraldStart().titleKey(), "the pack's lines are inherited");
        assertFalse(CalendarHerald.shows(quieted, run("contest", 1)), "and none of them shows");
    }

    @Test
    void theHeraldsTwoKnobsDeclareTheirDefaultsForTheEditor() {
        ObjectSchema herald = CalendarEventAsset.Herald.CODEC.toSchema(new SchemaContext());
        assertEquals(Boolean.TRUE, ((BooleanSchema) herald.getProperties().get("Enabled")).getDefault());
        assertEquals(Boolean.FALSE, ((BooleanSchema) herald.getProperties().get("FirstRunOfYear")).getDefault());
        assertNotNull(herald.getProperties().get("Enabled").getMarkdownDescription());
        assertNotNull(herald.getProperties().get("FirstRunOfYear").getMarkdownDescription());
    }
}
