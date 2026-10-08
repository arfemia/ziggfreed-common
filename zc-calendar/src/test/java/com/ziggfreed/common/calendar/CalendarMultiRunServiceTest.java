package com.ziggfreed.common.calendar;

import static com.ziggfreed.common.calendar.CalendarFixtures.at;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ziggfreed.common.calendar.asset.CalendarEventAsset;
import com.ziggfreed.common.calendar.asset.CalendarEventConfig;
import com.ziggfreed.common.calendar.tick.CalendarTick;
import com.ziggfreed.common.calendar.tick.CalendarTransitions;
import com.ziggfreed.common.occurrence.Occurrence;

/**
 * The calendar's answers for an event that comes round several times a year: a spring and an autumn fair, the
 * traveling fair on the first Sunday of every month, the fishing contest every Sunday afternoon, a second run
 * crossing the new year, and a force bringing the year's next run forward (or running a finished one again for its
 * usual length) and stopping when it is over. Every run is (event, year, number), and a monthly run's number is
 * its month, a weekly run's its calendar week.
 */
class CalendarMultiRunServiceTest {

    /** April 10th to 16th and September 10th to 16th, every year: runs 4 and 9, by their months. */
    private static final String TWO_FAIRS = """
            { "Window": { "Rule": { "Type": "Monthly", "Day": 10, "Days": 7, "Months": [4, 9] } }, "FirstYear": 2026 }
            """;

    /** June 20th to July 6th, and December 20th to January 5th: runs 6 and 12. */
    private static final String CROSSING_FAIRS = """
            { "Window": { "Rule": { "Type": "Monthly", "Day": 20, "Days": 17, "Months": [6, 12] } }, "FirstYear": 2026 }
            """;

    private static final String TRAVELING_FAIR = """
            { "Window": { "Rule": { "Type": "Monthly", "Weekday": "Sunday", "Nth": 1, "Days": 7 } }, "FirstYear": 2027 }
            """;

    private static final String FISHING_CONTEST = """
            { "Window": { "Rule": { "Type": "Weekly", "Weekday": "Sunday", "At": "14:00", "Length": "PT2H" } },
              "FirstYear": 2026 }
            """;

    private final CalendarService service =
            new CalendarService(CalendarEventConfig.getInstance(), CalendarForces.getInstance());

    @BeforeEach
    void start() {
        CalendarFixtures.reset();
    }

    @AfterEach
    void clear() {
        CalendarFixtures.reset();
    }

    private static void load(String id, String json) {
        CalendarFixtures.loadEvents(Map.of(id.toLowerCase(Locale.ROOT), CalendarFixtures.event(id, json)));
    }

    private static List<String> labels(List<Occurrence> runs) {
        return runs.stream().map(Occurrence::label).toList();
    }

    @Test
    void aSpringAndAnAutumnFairAreTheRunsOfTheirMonths() {
        load("Two_Fairs", TWO_FAIRS);
        Occurrence autumn = service.live("two_fairs", at("2026-09-11T12:00:00Z"));
        assertNotNull(autumn);
        assertEquals(2026, autumn.year());
        assertEquals(9, autumn.number(), "a monthly run is numbered by its month, never its count");
        assertEquals(at("2026-09-10T00:00:00Z"), autumn.startMs());
        assertEquals(at("2026-09-17T00:00:00Z"), autumn.endMs());
        long june = at("2026-06-01T12:00:00Z");
        assertNull(service.live("two_fairs", june), "between the fairs nothing runs");
        assertEquals("2026#9", service.next("two_fairs", june).label());
        assertEquals("2027#4", service.next("two_fairs", at("2026-09-11T12:00:00Z")).label(),
                "after the autumn run, next spring's");
        assertEquals(List.of("2026#4", "2026#9"), labels(service.history("two_fairs", at("2026-10-01T00:00:00Z"))));
        assertEquals(Integer.valueOf(2026), service.currentYear("two_fairs", at("2026-09-11T12:00:00Z")),
                "every run of a year answers that year: one yearly copy stands for both");
        assertTrue(service.datesMove("two_fairs"), "two runs a year: no one run's days stand for every year");
        assertEquals("2026#9", service.after("two_fairs", 2026, 4).label(), "the run after spring is autumn");
        assertEquals("2027#4", service.after("two_fairs", 2026, 9).label());
        assertEquals(at("2027-04-10T00:00:00Z"), service.after("two_fairs", 2026, 9).startMs());
        assertEquals("2026#9", service.after("two_fairs", 2026, 6).label(),
                "a month the year has no run in is followed by the year's next run after it");
        assertNull(service.after("no_such_event", 2026, 4));
    }

    @Test
    void theTravelingFairRunsTheFirstSundayOfEveryMonthForAWeek() {
        load("Traveling_Fair", TRAVELING_FAIR);
        long october = at("2027-10-05T12:00:00Z");
        Occurrence run = service.live("traveling_fair", october);
        assertNotNull(run);
        assertEquals("2027#10", run.label());
        assertEquals(at("2027-10-03T00:00:00Z"), run.startMs());
        assertEquals(at("2027-10-10T00:00:00Z"), run.endMs(), "seven days, both ends counted");
        assertEquals(at("2027-11-07T00:00:00Z"), service.next("traveling_fair", october).startMs());
        Occurrence january = service.next("traveling_fair", at("2027-12-20T12:00:00Z"));
        assertEquals("2028", january.label(), "the next year's January run is its run 1");
        assertEquals(at("2028-01-02T00:00:00Z"), january.startMs());
        assertEquals(10, service.history("traveling_fair", october).size());
    }

    @Test
    void theFishingContestRunsEverySundayFromTwoForTwoHours() {
        load("Fishing_Contest", FISHING_CONTEST);
        Occurrence contest = service.live("fishing_contest", at("2026-10-11T15:00:00Z"));
        assertNotNull(contest);
        assertEquals("2026#41", contest.label(), "the Sunday ending the year's forty-first week");
        assertEquals(at("2026-10-11T16:00:00Z"), contest.endMs());
        assertNull(service.live("fishing_contest", at("2026-10-11T16:00:00Z")), "over at four");
        assertNull(service.live("fishing_contest", at("2026-10-11T12:00:00Z")), "not before two");
        Occurrence next = service.next("fishing_contest", at("2026-10-11T16:00:00Z"));
        assertEquals("2026#42", next.label());
        assertEquals(at("2026-10-18T14:00:00Z"), next.startMs());
    }

    // Review Focus 5, several runs a year: a run crossing the new year is its starting year's, by number too.
    @Test
    void aSecondRunCrossingTheNewYearBelongsToTheYearItStarted() {
        load("Two_Fairs", CROSSING_FAIRS);
        long january = at("2027-01-03T12:00:00Z");
        Occurrence run = service.live("two_fairs", january);
        assertNotNull(run);
        assertEquals("2026#12", run.label(), "early January is still 2026's December run");
        assertTrue(run.sameRun(service.live("two_fairs", at("2026-12-22T12:00:00Z"))), "one run on both sides of midnight");
        assertEquals(Integer.valueOf(2026), service.currentYear("two_fairs", january));
        assertEquals(List.of("2026#6", "2026#12"), labels(service.history("two_fairs", january)), "no 2027 run has begun");
        Occurrence next = service.next("two_fairs", january);
        assertEquals("2027#6", next.label());
        assertEquals(at("2027-06-20T00:00:00Z"), next.startMs());
        long newYear = at("2027-01-01T00:01:00Z");
        CalendarTick tick = CalendarTransitions.diff(service.liveAll(at("2026-12-31T23:59:00Z")),
                service.liveAll(newYear), service::isEnabled, newYear, false);
        assertFalse(tick.changed(), "the new year starts no run and ends none");
    }

    // Review Focus 2, several runs a year: a force runs the year's next run not yet over, with its own days and
    // number; forced again, or re-dated by the owner, it is the same (event, year, number).
    @Test
    void aForcedRunIsTheYearsNextRunAndKeepsItsNumberWhenForcedAgainOrReDated() {
        load("Two_Fairs", TWO_FAIRS);
        long today = at("2026-09-02T12:00:00Z");
        CalendarForces forces = CalendarForces.getInstance();

        Occurrence forced = service.forceOn("Two_Fairs", today);
        assertNotNull(forced);
        assertEquals(forced, service.live("two_fairs", today), "the force runs the run it took");
        assertEquals("2026#9", forced.label(), "spring is over, so the force brings the autumn run forward");
        assertEquals(at("2026-09-10T00:00:00Z"), forced.startMs(), "with its own days");
        assertEquals(Integer.valueOf(2026), service.currentYear("two_fairs", today), "the year a copy is minted for");
        assertEquals(List.of("2026#4", "2026#9"), labels(service.history("two_fairs", today)), "listed once");
        assertEquals("2027#4", service.next("two_fairs", today).label(), "the run after the forced one is next spring's");

        forces.forceOff("Two_Fairs");
        assertNull(service.live("two_fairs", today));
        service.forceOn("Two_Fairs", today);
        assertTrue(forced.sameRun(service.live("two_fairs", today)), "forced on again, the same run");

        // The owner moves both runs to the 1st of their months, the autumn one now around today; another day of
        // the month keeps a monthly run's number.
        CalendarEventConfig config = CalendarEventConfig.getInstance();
        config.mergeOwnerLayer(Map.of("two_fairs", CalendarFixtures.event("Two_Fairs",
                "{ \"Window\": { \"Rule\": { \"Day\": 1 } } }", config.resolve("two_fairs"))));
        Occurrence redated = service.live("two_fairs", today);
        assertTrue(forced.sameRun(redated), "moved to September 1st, the autumn run is still the year's run 9");
        assertEquals(at("2026-09-01T00:00:00Z"), redated.startMs(), "on the owner's days");
        forces.clear("Two_Fairs");
        assertTrue(forced.sameRun(service.live("two_fairs", today)), "the owner's dates alone now hold the same run");
    }

    // Review Focus 2: a run brought forward that the owner moves to days still ahead runs on those days.
    @Test
    void aForcedRunTheOwnerMovesToDaysStillAheadRunsAndEndsOnThem() {
        load("Two_Fairs", TWO_FAIRS);
        long june = at("2026-06-01T12:00:00Z");
        Occurrence forced = service.forceOn("Two_Fairs", june);
        CalendarEventConfig config = CalendarEventConfig.getInstance();
        config.mergeOwnerLayer(Map.of("two_fairs", CalendarFixtures.event("Two_Fairs",
                "{ \"Window\": { \"Rule\": { \"Day\": 20 } } }", config.resolve("two_fairs"))));
        Occurrence moved = service.live("two_fairs", june);
        assertTrue(forced.sameRun(moved), "the same run 9, still brought forward");
        assertEquals(at("2026-09-20T00:00:00Z"), moved.startMs(), "on the owner's days, still ahead");
        assertEquals(at("2026-09-27T00:00:00Z"), moved.endMs());
        assertEquals(Long.valueOf(at("2026-09-27T00:00:00Z")), service.forceEndsMs("two_fairs", june),
                "and the force ends with them");
        assertEquals("2026#9", service.live("two_fairs", at("2026-09-18T12:00:00Z")).label(),
                "past its old days, before its new ones, it is still forced");
        assertNull(service.live("two_fairs", at("2026-09-27T00:00:00Z")), "over with its new days");
    }

    @Test
    void aForceAfterTheYearsLastRunRunsThatRunAgainNeverANewOne() {
        load("Two_Fairs", TWO_FAIRS);
        CalendarForces forces = CalendarForces.getInstance();
        long october = at("2026-10-15T12:00:00Z");
        service.forceOn("Two_Fairs", october);
        assertEquals("2026#9", service.live("two_fairs", october).label());
        assertEquals(List.of("2026#4", "2026#9"), labels(service.history("two_fairs", october)), "and lists it once");
        forces.clear("Two_Fairs");
        long march = at("2026-03-01T12:00:00Z");
        service.forceOn("Two_Fairs", march);
        assertEquals("2026#4", service.live("two_fairs", march).label(),
                "forced before the spring fair, it is the spring fair");
    }

    // M330, "next run, then stop": the force keeps the run it took and is over once that run's days end. No read
    // writes the force: the tick's look clears it.
    @Test
    void aForceStopsWhenTheRunItBroughtForwardIsOverAndNeverWalksOn() {
        load("Two_Fairs", TWO_FAIRS);
        service.forceOn("Two_Fairs", at("2026-06-01T12:00:00Z"));
        assertEquals("2026#9", service.live("two_fairs", at("2026-06-01T12:00:00Z")).label());
        assertEquals("2026#9", service.live("two_fairs", at("2026-08-01T12:00:00Z")).label(), "it keeps the run it took");
        long lastMinute = at("2026-09-16T23:59:00Z");
        assertEquals("2026#9", service.live("two_fairs", lastMinute).label(), "through the run's last day");
        Map<String, Occurrence> before = service.liveAll(lastMinute);

        long over = at("2026-09-17T00:01:00Z");
        CalendarTick tick = CalendarTransitions.diff(before, service.liveAll(over), service::isEnabled, over, false);
        assertEquals(1, tick.ended().size(), "the forced run ends when its days do");
        assertFalse(tick.ended().get(0).switchedOff(), "a real end");
        assertTrue(tick.started().isEmpty(), "and the force brings no other run forward");
        assertEquals(Boolean.TRUE, service.forced("two_fairs"), "no read writes the force");
        assertNull(service.forceEndsMs("two_fairs", over), "though it is in force no more");
        service.clearSpentForces(over);
        assertNull(service.forced("two_fairs"), "the tick's look clears it");
        assertNull(service.live("two_fairs", at("2026-10-01T12:00:00Z")), "so the event follows its dates again");
        assertEquals("2027#4", service.next("two_fairs", over).label());
    }

    @Test
    void aForceTakenDuringARunStopsWithIt() {
        load("Two_Fairs", TWO_FAIRS);
        long during = at("2026-04-12T12:00:00Z");
        assertEquals("2026#4", service.forceOn("Two_Fairs", during).label(), "the run going on");
        long over = at("2026-04-17T00:00:00Z");
        assertNull(service.live("two_fairs", over), "over with its days: the force never moves on to the autumn run");
        service.clearSpentForces(over);
        assertNull(service.forced("two_fairs"));
    }

    // M284 (the maintainer): a run forced again after its days were over runs its usual length from the force.
    @Test
    void aReplayRunsItsUsualLengthFromTheForceThenStops() {
        load("Two_Fairs", TWO_FAIRS);
        long forcedAt = at("2026-10-15T12:00:00Z");
        Occurrence replay = service.forceOn("Two_Fairs", forcedAt);
        assertEquals("2026#9", replay.label(), "after the year's last run, the force runs it again");
        assertEquals(at("2026-09-10T00:00:00Z"), replay.startMs(), "as itself, its own days kept");
        long sevenDaysOn = at("2026-10-22T12:00:00Z");
        assertEquals(Long.valueOf(sevenDaysOn), service.forceEndsMs("two_fairs", forcedAt),
                "its usual seven days, counted from the force");
        assertEquals("2026#9", service.live("two_fairs", sevenDaysOn - 60_000L).label());
        assertNull(service.live("two_fairs", sevenDaysOn), "then it stops by itself");
        assertNull(service.live("two_fairs", at("2027-02-01T12:00:00Z")), "never running on towards the next run");
        service.clearSpentForces(sevenDaysOn);
        assertNull(service.forced("two_fairs"));
    }

    // A replay never outlasts the event's next run: once that begins by its dates, the replay is over.
    @Test
    void aReplayStopsWhenTheEventsNextRunBeginsBeforeItsLengthIsUp() {
        load("Winter_Market", """
                { "Window": { "Rule": { "Type": "Monthly", "Day": 1, "Days": 2, "Months": [12] },
                              "Years": { "2027": { "Start": "01-01", "End": "01-01" } } }, "FirstYear": 2026 }
                """);
        long forcedAt = at("2026-12-31T12:00:00Z");
        assertEquals("2026#12", service.forceOn("Winter_Market", forcedAt).label(), "December's run, two days again");
        assertEquals(Long.valueOf(at("2027-01-01T00:00:00Z")), service.forceEndsMs("winter_market", forcedAt),
                "cut short by 2027's run on New Year's Day");
        assertEquals("2027", service.live("winter_market", at("2027-01-01T12:00:00Z")).label(), "the dates' own run");
        assertNull(service.live("winter_market", at("2027-01-02T06:00:00Z")),
                "and the finished run never comes back after it, though its two days are not yet up");
    }

    // Minor 1: whether a force is a replay is decided as it is written, never from days the owner moves later.
    @Test
    void aForcedRunReDatedIntoThePastStopsWithItsNewDaysAndNeverBecomesAReplay() {
        load("Two_Fairs", TWO_FAIRS);
        long ninth = at("2026-09-09T12:00:00Z");
        assertEquals(at("2026-09-10T00:00:00Z"), service.forceOn("Two_Fairs", ninth).startMs(), "brought forward a day");
        CalendarEventConfig config = CalendarEventConfig.getInstance();
        config.mergeOwnerLayer(Map.of("two_fairs", CalendarFixtures.event("Two_Fairs",
                "{ \"Window\": { \"Rule\": { \"Day\": 1 } } }", config.resolve("two_fairs"))));
        assertNull(service.live("two_fairs", ninth), "its re-dated days ended the day before it was forced");
        assertNull(service.forceEndsMs("two_fairs", ninth));
        assertNull(service.live("two_fairs", at("2026-09-12T12:00:00Z")), "and it never runs on as a replay");
    }

    @Test
    void aForcedRunTheOwnerTakesAwayEndsTheForceAtTheTicksLook() {
        load("Two_Fairs", TWO_FAIRS);
        long june = at("2026-06-01T12:00:00Z");
        assertEquals("2026#9", service.forceOn("Two_Fairs", june).label());
        CalendarEventConfig config = CalendarEventConfig.getInstance();
        config.mergeOwnerLayer(Map.of("two_fairs", CalendarFixtures.event("Two_Fairs",
                "{ \"Window\": { \"Rule\": { \"Months\": [4, 10] } } }", config.resolve("two_fairs"))));
        assertNull(service.live("two_fairs", june), "September has no run now, so the forced run is gone");
        assertEquals(Boolean.TRUE, service.forced("two_fairs"), "a read writes nothing");
        service.clearSpentForces(june);
        assertNull(service.forced("two_fairs"), "the tick's look ends the force");
    }

    // Review 1: a reader that catches a reload half done (the owner's layer emptied, not yet refilled) never
    // ends a force; it only answers by the dates it read.
    @Test
    void aReloadCaughtHalfDoneNeverEndsAForce() {
        load("Two_Fairs", TWO_FAIRS);
        CalendarEventConfig config = CalendarEventConfig.getInstance();
        Map<String, CalendarEventAsset> owned = Map.of("two_fairs", CalendarFixtures.event("Two_Fairs",
                "{ \"Window\": { \"Rule\": { \"Months\": [4, 10] } } }", config.resolve("two_fairs")));
        config.mergeOwnerLayer(owned);
        long june = at("2026-06-01T12:00:00Z");
        Occurrence forced = service.forceOn("Two_Fairs", june);
        assertEquals("2026#10", forced.label(), "the owner's October run");

        config.mergeOwnerLayer(Map.of());
        assertNull(service.live("two_fairs", june), "read in the gap, the pack's dates have no run 10");
        service.history("two_fairs", june);
        service.next("two_fairs", june);
        service.currentYear("two_fairs", june);
        service.liveAll(june);
        assertEquals(Boolean.TRUE, service.forced("two_fairs"), "no read writes the force");

        config.mergeOwnerLayer(owned);
        assertTrue(forced.sameRun(service.live("two_fairs", june)), "the force still stands once the layer returns");
    }

    @Test
    void oneEventsFailureCostsOnlyItsOwnAnswerAndIsSaidOnce() {
        List<String> said = new ArrayList<>();
        boolean[] broken = {true};
        Function<String, String> answer = id -> {
            if (id.equals("broken") && broken[0]) {
                throw new IllegalStateException("a broken event");
            }
            return "running";
        };
        List<String> ids = List.of("broken", "fine");
        assertEquals(Map.of("fine", "running"), service.eachEvent(ids, answer, (line, cause) -> said.add(line)),
                "the event after the broken one is still asked");
        service.eachEvent(ids, answer, (line, cause) -> said.add(line));
        assertEquals(1, said.size(), "said once, not every tick");
        assertTrue(said.get(0).contains("broken"), "naming the event");
        broken[0] = false;
        assertEquals(Map.of("broken", "running", "fine", "running"),
                service.eachEvent(ids, answer, (line, cause) -> said.add(line)));
        broken[0] = true;
        service.eachEvent(ids, answer, (line, cause) -> said.add(line));
        assertEquals(2, said.size(), "broken again after it answered, said again");
    }
}
