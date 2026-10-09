package com.ziggfreed.common.calendar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.ZoneOffset;
import java.util.ArrayList;
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

    // A Saturday contest's 2026 runs from January 3rd (week 1) to December 26th (week 52), a day each.
    @Test
    void anEventThatSaysFirstRunOfYearShowsItsStartBannerForItsFirstRunAndItsEndBannerWhenItsLastEnds() {
        CalendarEventAsset contest = withBoth("Contest", ", \"FirstRunOfYear\": true");
        assertTrue(CalendarHerald.showsStart(contest, run("contest", 1)));
        assertFalse(CalendarHerald.showsStart(contest, run("contest", 2)), "the year's later runs come and go quietly");
        Map<String, CalendarEventAsset> events = Map.of("contest", contest);
        assertTrue(CalendarHerald.startQueue(List.of(run("contest", 2)), events::get, 0L).isEmpty());
        assertEquals(1, CalendarHerald.startQueue(List.of(run("contest", 1)), events::get, 0L).size());
        CalendarTick secondEnds = new CalendarTick(2L, false, Set.of(), List.of(),
                List.of(new CalendarTick.Ended(run("contest", 2), false)));
        assertTrue(CalendarHerald.endQueue(secondEnds, events::get).isEmpty());
        List<AnnualWindow.DatedRun> year = contest.annualWindow().datedRuns(2026);
        assertEquals(52, year.get(year.size() - 1).number(), "December 26th's run is the year's last");
        CalendarTick firstEnds = new CalendarTick(2L, false, Set.of(), List.of(),
                List.of(new CalendarTick.Ended(run("contest", 1), false)));
        assertTrue(CalendarHerald.endQueue(firstEnds, events::get).isEmpty(), "the first run ends quietly");
        CalendarTick lastEnds = new CalendarTick(2L, false, Set.of(), List.of(),
                List.of(new CalendarTick.Ended(run("contest", 52), false)));
        assertEquals(List.of("Contest.end"), titles(CalendarHerald.endQueue(lastEnds, events::get)),
                "the year's last run ends with the banner");
        assertFalse(CalendarHerald.showsStart(contest, run("contest", 52)), "and opens without one");
        assertTrue(CalendarHerald.showsStart(withBoth("Contest", ""), run("contest", 7)),
                "unsaid, every run shows them");
        assertTrue(CalendarHerald.showsEnd(withBoth("Contest", ""), run("contest", 7)), "both of them");
    }

    // A list of spans numbers each run by its place, wherever its days fall: written October, February, June, runs 1,
    // 2 and 3 come round in the order 2, 3, 1. The year's first by its dates is run 2 and its last run 1.
    @Test
    void theYearsFirstAndLastRunsAreFoundByTheirDatesNeverByTheirNumbers() {
        CalendarEventAsset fair = CalendarFixtures.event("Fair", "{ \"Window\": { \"Rule\": { \"Type\": \"Fixed\","
                + " \"Runs\": [ { \"Start\": \"10-01\", \"End\": \"10-07\" },"
                + " { \"Start\": \"02-01\", \"End\": \"02-07\" },"
                + " { \"Start\": \"06-01\", \"End\": \"06-07\" } ] } }, \"FirstYear\": 2026, \"Herald\": {"
                + " \"Start\": { \"TitleKey\": \"fair.start\" }, \"End\": { \"TitleKey\": \"fair.end\" },"
                + " \"FirstRunOfYear\": true } }");
        Map<String, CalendarEventAsset> events = Map.of("fair", fair);
        List<Integer> opened = new ArrayList<>();
        List<Integer> closed = new ArrayList<>();
        for (AnnualWindow.DatedRun dated : fair.annualWindow().datedRuns(2026)) {
            Occurrence run = dated.occurrence("fair", ZoneOffset.UTC);
            if (!CalendarHerald.startQueue(List.of(run), events::get, 0L).isEmpty()) {
                opened.add(run.number());
            }
            CalendarTick ends = new CalendarTick(run.endMs(), false, Set.of(), List.of(),
                    List.of(new CalendarTick.Ended(run, false)));
            if (!CalendarHerald.endQueue(ends, events::get).isEmpty()) {
                closed.add(run.number());
            }
        }
        assertEquals(List.of(2), opened, "the start banner opens February's run, the year's first");
        assertEquals(List.of(1), closed, "the end banner closes October's run, the year's last");
        assertFalse(CalendarHerald.showsStart(fair, new Occurrence("fair", 2026, 3, 0L, 1L)), "June's run");
        assertFalse(CalendarHerald.showsEnd(fair, new Occurrence("fair", 2026, 3, 0L, 1L)), "comes and goes quietly");
    }

    // A Saturday contest whose runs follow back to back: 2026's last (December 26th, week 52) lasts until 2027's first
    // (January 2nd, week 1) begins, so one tick ends the one and starts the other.
    @Test
    void aYearsLastRunFollowedAtOnceByTheNextYearsFirstShowsOnlyTheNextYearsStartBanner() {
        CalendarEventAsset contest = CalendarFixtures.event("Contest", "{ \"Window\": { \"Rule\": { \"Type\":"
                + " \"Weekly\", \"Weekday\": \"Saturday\", \"UntilNext\": true } }, \"FirstYear\": 2026, \"Herald\": {"
                + " \"Start\": { \"TitleKey\": \"contest.start\" }, \"End\": { \"TitleKey\": \"contest.end\" },"
                + " \"FirstRunOfYear\": true } }");
        List<AnnualWindow.DatedRun> year = contest.annualWindow().datedRuns(2026);
        AnnualWindow.DatedRun last = year.get(year.size() - 1);
        AnnualWindow.DatedRun next = contest.annualWindow().datedRuns(2027).get(0);
        assertEquals(List.of(52, 1), List.of(last.number(), next.number()));
        assertEquals(last.days().end(), next.days().start(), "back to back across the new year");
        Occurrence ending = last.occurrence("contest", ZoneOffset.UTC);
        Occurrence starting = next.occurrence("contest", ZoneOffset.UTC);
        assertTrue(CalendarHerald.showsEnd(contest, ending), "alone, the year's last run would end with the banner");
        Map<String, CalendarEventAsset> events = Map.of("contest", contest);
        CalendarTick tick = new CalendarTick(ending.endMs(), false, Set.of("contest"),
                List.of(new CalendarTick.Started(starting, false)), List.of(new CalendarTick.Ended(ending, false)));
        assertTrue(CalendarHerald.endQueue(tick, events::get).isEmpty(), "the next run's start banner speaks for it");
        List<CalendarHerald.QueuedBanner> starts = CalendarHerald.startQueue(List.of(starting), events::get,
                CalendarHerald.startsAfterMs(tick, events::get));
        assertEquals(List.of("contest.start"), titles(starts), "2027's first run opens with its banner");
        assertEquals(List.of(0L), delays(starts), "at once, with no end banner before it");
    }

    @Test
    void anEventWithItsHeraldSwitchedOffShowsNoBanner() {
        CalendarEventAsset quiet = withBoth("Contest", ", \"Enabled\": false");
        assertFalse(CalendarHerald.showsStart(quiet, run("contest", 1)));
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
        assertTrue(CalendarHerald.showsStart(mondays, run("contest", 2)), "January 5th's run is the year's first");
        assertFalse(CalendarHerald.showsStart(mondays, run("contest", 3)));
    }

    @Test
    void anOwnerQuietsAPacksEventByItsHeraldAloneAndKeepsItsLines() {
        CalendarEventAsset quieted = CalendarFixtures.event("Contest", "{ \"Herald\": { \"Enabled\": false } }",
                withBoth("Contest", ""));
        assertEquals("Contest.start", quieted.heraldStart().titleKey(), "the pack's lines are inherited");
        assertFalse(CalendarHerald.showsStart(quieted, run("contest", 1)), "and none of them shows");
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
