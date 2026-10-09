package com.ziggfreed.common.calendar.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import javax.annotation.Nonnull;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.event.IEvent;
import com.ziggfreed.common.event.NativeEventSeam;
import com.ziggfreed.common.occurrence.Occurrence;

/** What a start and an end tell a listener: every fact of the run, and why it ended. */
class CalendarEventsTest {

    private static final Occurrence EVE_2026 = new Occurrence("Hallows_Eve", 2026, 1_000L, 2_000L);

    private final List<IEvent<Void>> fired = new ArrayList<>();

    @BeforeEach
    void observe() {
        CalendarEvents.SEAM.publishTo(new NativeEventSeam.Publisher() {
            @Override
            public <E extends IEvent<Void>> void publish(@Nonnull Class<E> type, @Nonnull Supplier<E> build) {
                fired.add(build.get());
            }
        });
    }

    @AfterEach
    void restoreTheBus() {
        CalendarEvents.SEAM.publishTo(null);
    }

    @Test
    void aStartCarriesEveryFactOfItsRun() {
        CalendarEvents.fireStarted(EVE_2026, true, 1_500L);
        assertEquals(1, fired.size());
        CalendarEventStartedEvent started = assertInstanceOf(CalendarEventStartedEvent.class, fired.get(0));
        assertEquals("hallows_eve", started.eventId());
        assertEquals(2026, started.year());
        assertEquals(1_000L, started.startMs());
        assertEquals(2_000L, started.endMs());
        assertTrue(started.resumed(), "the boot's catch-up start says so");
        assertEquals(1_500L, started.firedAtMs());
    }

    @Test
    void anEndSaysWhetherItsEventWasSwitchedOff() {
        CalendarEvents.fireEnded(EVE_2026, true, 1_800L);
        CalendarEvents.fireEnded(EVE_2026, false, 2_000L);
        CalendarEventEndedEvent switchedOff = assertInstanceOf(CalendarEventEndedEvent.class, fired.get(0));
        assertEquals("hallows_eve", switchedOff.eventId());
        assertEquals(2026, switchedOff.year());
        assertTrue(switchedOff.switchedOff(), "the event went away; its run did not end");
        assertEquals(1_800L, switchedOff.firedAtMs());
        assertFalse(assertInstanceOf(CalendarEventEndedEvent.class, fired.get(1)).switchedOff(),
                "its dates ran out");
    }

    @Test
    void anAttendanceNamesThePlayerAndTheRunTheyWereHereFor() {
        UUID player = UUID.randomUUID();
        CalendarEvents.fireAttended(player, EVE_2026, 1_200L);
        assertEquals(1, fired.size());
        CalendarAttendedEvent attended = assertInstanceOf(CalendarAttendedEvent.class, fired.get(0));
        assertEquals(player, attended.playerId());
        assertEquals("hallows_eve", attended.eventId());
        assertEquals(2026, attended.year(), "the year the run began in");
        assertEquals(1_200L, attended.firedAtMs());
    }

    @Test
    void aLaterRunOfAYearCarriesItsNumberOnEveryEvent() {
        Occurrence autumn = new Occurrence("Two_Fairs", 2026, 2, 3_000L, 4_000L);
        UUID player = UUID.randomUUID();
        CalendarEvents.fireStarted(autumn, false, 3_000L);
        CalendarEvents.fireEnded(autumn, false, 4_000L);
        CalendarEvents.fireAttended(player, autumn, 3_100L);
        assertEquals(2, assertInstanceOf(CalendarEventStartedEvent.class, fired.get(0)).number());
        assertEquals(2, assertInstanceOf(CalendarEventEndedEvent.class, fired.get(1)).number());
        CalendarAttendedEvent attended = assertInstanceOf(CalendarAttendedEvent.class, fired.get(2));
        assertEquals(2026, attended.year(), "still the year the run began in");
        assertEquals(2, attended.number());
        assertEquals(1, new CalendarAttendedEvent(player, "hallows_eve", 2026, 1L).number(), "the old form is run 1");
    }
}
