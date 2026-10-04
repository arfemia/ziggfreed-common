package com.ziggfreed.common.calendar.event;

import javax.annotation.Nonnull;

import com.hypixel.hytale.event.IEvent;

/**
 * A calendar event's run is over: its dates ran out, an administrator stopped it, or the owner switched
 * the event off mid-run ({@link #switchedOff()}, after which the event is absent rather than ended).
 *
 * <p>Fired on the calendar's tick thread, never a world thread (see {@link CalendarEventStartedEvent}).
 */
public final class CalendarEventEndedEvent implements IEvent<Void> {

    private final String eventId;
    private final int year;
    private final boolean switchedOff;
    private final long firedAtMs;

    public CalendarEventEndedEvent(@Nonnull String eventId, int year, boolean switchedOff, long firedAtMs) {
        this.eventId = eventId;
        this.year = year;
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

    /** True when the owner switched the event off (it is now absent); false when its dates ran out or a command stopped it. */
    public boolean switchedOff() {
        return switchedOff;
    }

    public long firedAtMs() {
        return firedAtMs;
    }
}
