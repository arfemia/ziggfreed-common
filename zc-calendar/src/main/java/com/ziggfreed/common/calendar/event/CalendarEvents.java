package com.ziggfreed.common.calendar.event;

import java.util.UUID;

import javax.annotation.Nonnull;

import com.ziggfreed.common.event.NativeEventSeam;
import com.ziggfreed.common.occurrence.Occurrence;

/**
 * Where the calendar's native events go: the shared engine bus, through one {@link NativeEventSeam}
 * (built only when something listens, never throwing). A test observes them with {@code publishTo}.
 */
public final class CalendarEvents {

    /** The calendar's event family; a test routes it with {@code publishTo}. */
    public static final NativeEventSeam SEAM = new NativeEventSeam("[calendar]");

    private CalendarEvents() {
    }

    public static void fireStarted(@Nonnull Occurrence run, boolean resumed, long nowMs) {
        SEAM.fire("CalendarEventStarted", CalendarEventStartedEvent.class,
                () -> new CalendarEventStartedEvent(run.eventId(), run.year(), run.number(), run.startMs(),
                        run.endMs(), resumed, nowMs));
    }

    public static void fireEnded(@Nonnull Occurrence run, boolean switchedOff, long nowMs) {
        SEAM.fire("CalendarEventEnded", CalendarEventEndedEvent.class,
                () -> new CalendarEventEndedEvent(run.eventId(), run.year(), run.number(), switchedOff, nowMs));
    }

    public static void fireAttended(@Nonnull UUID playerId, @Nonnull Occurrence run, long nowMs) {
        SEAM.fire("CalendarAttended", CalendarAttendedEvent.class,
                () -> new CalendarAttendedEvent(playerId, run.eventId(), run.year(), run.number(), nowMs));
    }
}
