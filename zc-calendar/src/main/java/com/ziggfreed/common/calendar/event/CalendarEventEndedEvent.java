package com.ziggfreed.common.calendar.event;

import javax.annotation.Nonnull;

import com.hypixel.hytale.event.IEvent;

/**
 * A calendar event's run is over: its dates ran out, an administrator stopped it, or the owner switched
 * the event off mid-run ({@link #switchedOff()}, after which the event is absent rather than ended).
 * Several runs of one year differ by {@link #number()}.
 *
 * <p>Fired on the calendar's tick thread, never a world thread (see {@link CalendarEventStartedEvent}).
 */
public final class CalendarEventEndedEvent implements IEvent<Void> {

    private final String eventId;
    private final int year;
    private final int number;
    private final boolean switchedOff;
    private final long firedAtMs;

    /** The year's run 1: what an event that comes round once a year has. */
    public CalendarEventEndedEvent(@Nonnull String eventId, int year, boolean switchedOff, long firedAtMs) {
        this(eventId, year, 1, switchedOff, firedAtMs);
    }

    public CalendarEventEndedEvent(@Nonnull String eventId, int year, int number, boolean switchedOff,
            long firedAtMs) {
        this.eventId = eventId;
        this.year = year;
        this.number = number;
        this.switchedOff = switchedOff;
        this.firedAtMs = firedAtMs;
    }

    @Nonnull
    public String eventId() {
        return eventId;
    }

    public int year() {
        return year;
    }

    /**
     * The run's number within its year, from 1: the event's dates name it (a month, a calendar week, a span's
     * place), so it is a name, never a count, and a year's numbers may skip. An event that comes round once a
     * year only has run 1.
     */
    public int number() {
        return number;
    }

    /** True when the owner switched the event off (it is now absent); false when its dates ran out or a command stopped it. */
    public boolean switchedOff() {
        return switchedOff;
    }

    public long firedAtMs() {
        return firedAtMs;
    }
}
