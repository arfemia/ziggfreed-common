package com.ziggfreed.common.calendar.event;

import javax.annotation.Nonnull;

import com.hypixel.hytale.event.IEvent;

/**
 * A calendar event's run has begun: its dates came round, an administrator forced it on, or the server
 * booted in the middle of it ({@link #resumed()}).
 *
 * <p>Synchronous {@code IEvent<Void>} POJO on the shared engine bus, fired once per run start through
 * {@link CalendarEvents}. It fires on the calendar's tick thread (the boot thread for a resumed start),
 * NEVER a world thread: a listener that touches a world hops onto it with {@code world.execute}.
 */
public final class CalendarEventStartedEvent implements IEvent<Void> {

    private final String eventId;
    private final int year;
    private final long startMs;
    private final long endMs;
    private final boolean resumed;
    private final long firedAtMs;

    public CalendarEventStartedEvent(@Nonnull String eventId, int year, long startMs, long endMs,
            boolean resumed, long firedAtMs) {
        this.eventId = eventId;
        this.year = year;
        this.startMs = startMs;
        this.endMs = endMs;
        this.resumed = resumed;
        this.firedAtMs = firedAtMs;
    }

    /** Which event, lower-cased. */
    @Nonnull
    public String eventId() {
        return eventId;
    }

    /** The year the run belongs to: the year it starts in. */
    public int year() {
        return year;
    }

    /** The run's first instant. */
    public long startMs() {
        return startMs;
    }

    /** The first instant after the run. */
    public long endMs() {
        return endMs;
    }

    /** True when the server booted with the run already going: nothing began, the server caught up. */
    public boolean resumed() {
        return resumed;
    }

    /** When the calendar noticed. */
    public long firedAtMs() {
        return firedAtMs;
    }
}
