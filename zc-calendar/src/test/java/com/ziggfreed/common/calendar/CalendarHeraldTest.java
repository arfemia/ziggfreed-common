package com.ziggfreed.common.calendar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.ziggfreed.common.calendar.asset.CalendarEventAsset;
import com.ziggfreed.common.calendar.tick.CalendarTick;
import com.ziggfreed.common.occurrence.Occurrence;

/** Which banner a moment owes: the start line on a first attendance, the end line on a real end only. */
class CalendarHeraldTest {

    private static final String WITH_LINES = """
            { "Window": { "Start": "10-01", "End": "11-03" }, "FirstYear": 2026,
              "Herald": { "Start": { "TitleKey": "test.start", "Major": true }, "End": { "TitleKey": "test.end" } } }
            """;

    private final CalendarEventAsset event = CalendarFixtures.event("Hallows_Eve", WITH_LINES);
    private final Occurrence run = new Occurrence("hallows_eve", 2026, 1L, 2L);

    private static List<String> titles(List<CalendarEventAsset.HeraldLine> lines) {
        return lines.stream().map(CalendarEventAsset.HeraldLine::titleKey).toList();
    }

    @Test
    void aRunThatRanOutOwesItsEndBanner() {
        CalendarTick tick = new CalendarTick(2L, false, Set.of(), List.of(), List.of(new CalendarTick.Ended(run, false)));
        assertEquals(List.of("test.end"), titles(CalendarHerald.endLines(tick, id -> event)));
    }

    @Test
    void aSwitchOffOwesNoBanner() {
        CalendarTick tick = new CalendarTick(2L, false, Set.of(), List.of(), List.of(new CalendarTick.Ended(run, true)));
        assertTrue(CalendarHerald.endLines(tick, id -> event).isEmpty(), "off means absent: the event just goes");
    }

    @Test
    void anEventWithNoEndLineOwesNothing() {
        CalendarEventAsset plain = CalendarFixtures.event("Hallows_Eve", CalendarFixtures.HALLOWS_EVE);
        CalendarTick tick = new CalendarTick(2L, false, Set.of(), List.of(), List.of(new CalendarTick.Ended(run, false)));
        assertTrue(CalendarHerald.endLines(tick, id -> plain).isEmpty());
    }

    @Test
    void theStartBannerIsTheStartLine() {
        assertEquals("test.start", CalendarHerald.startLine(event).titleKey());
        assertTrue(CalendarHerald.startLine(event).major());
        assertNull(CalendarHerald.startLine(null));
        assertNull(CalendarHerald.startLine(CalendarFixtures.event("Hallows_Eve", CalendarFixtures.HALLOWS_EVE)));
    }
}
