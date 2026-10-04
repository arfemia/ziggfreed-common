package com.ziggfreed.common.calendar.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
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
}
