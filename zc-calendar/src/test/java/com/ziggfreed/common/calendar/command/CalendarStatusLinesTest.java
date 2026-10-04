package com.ziggfreed.common.calendar.command;

import static com.ziggfreed.common.calendar.CalendarFixtures.at;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ziggfreed.common.calendar.CalendarFixtures;
import com.ziggfreed.common.calendar.CalendarForces;
import com.ziggfreed.common.calendar.CalendarService;
import com.ziggfreed.common.calendar.asset.CalendarEventConfig;
import com.ziggfreed.common.calendar.command.CalendarStatusLines.Line;

/** What an administrator reads about an event, as keys and raw data: running, waiting, forced, off, broken. */
class CalendarStatusLinesTest {

    private final CalendarService service =
            new CalendarService(CalendarEventConfig.getInstance(), CalendarForces.getInstance());

    @BeforeEach
    void load() {
        CalendarFixtures.reset();
        CalendarFixtures.loadDesignEvents();
    }

    @AfterEach
    void reset() {
        CalendarFixtures.reset();
    }

    @Test
    void aRunningEventSaysItsYearAndItsLastDay() {
        assertEquals(new Line("row.live", List.of("hallows_eve", "2026", "2026-11-03")),
                CalendarStatusLines.row(service, "Hallows_Eve", at("2026-10-02T12:00:00Z")));
    }

    @Test
    void aWaitingEventSaysWhenItNextStarts() {
        assertEquals(new Line("row.waiting", List.of("harvest_moon", "2026-10-29")),
                CalendarStatusLines.row(service, "harvest_moon", at("2026-10-02T12:00:00Z")));
    }

    @Test
    void forcedAndStoppedEventsSaySo() {
        long october = at("2026-10-02T12:00:00Z");
        CalendarForces.getInstance().force("harvest_moon", true);
        assertEquals("row.live.forced", CalendarStatusLines.row(service, "harvest_moon", october).key());
        CalendarForces.getInstance().force("hallows_eve", false);
        assertEquals(new Line("row.stopped", List.of("hallows_eve")), CalendarStatusLines.row(service, "hallows_eve", october));
    }

    @Test
    void aSwitchedOffEventAndAnUnknownOneAreTold() {
        CalendarEventConfig.getInstance().setGlobalEnabled(false);
        assertEquals(new Line("row.off", List.of("hallows_eve")), CalendarStatusLines.row(service, "hallows_eve", 0L));
        assertEquals(new Line("row.unknown", List.of("No_Such_Event")), CalendarStatusLines.row(service, "No_Such_Event", 0L));
    }

    @Test
    void anEventThatCannotRunSaysWhy() {
        CalendarFixtures.loadEvents(Map.of("broken", CalendarFixtures.event("Broken",
                "{ \"Window\": { \"Start\": \"10-01\", \"End\": \"10-02\" } }")));
        assertEquals(new Line("row.broken", List.of("broken", "FIRST_YEAR_MISSING")),
                CalendarStatusLines.row(service, "broken", 0L));
    }

    @Test
    void statusAddsTheDatesTheFirstYearAndTheRunsSoFar() {
        List<Line> lines = CalendarStatusLines.detail(service, "hallows_eve", at("2026-10-02T12:00:00Z"));
        assertEquals(List.of("row.live", "status.window", "status.first", "status.history"),
                lines.stream().map(Line::key).toList());
        assertEquals(List.of("10-01..11-03", "UTC"), lines.get(1).args());
        assertEquals(List.of("2026"), lines.get(2).args());
        assertEquals(List.of("2026"), lines.get(3).args());
    }
}
