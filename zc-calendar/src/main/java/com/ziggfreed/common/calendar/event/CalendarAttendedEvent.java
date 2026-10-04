package com.ziggfreed.common.calendar.event;

import java.util.UUID;

import javax.annotation.Nonnull;

import com.hypixel.hytale.event.IEvent;

/**
 * A player was on the server during a run of a calendar event for the first time that run: fired once
 * per player per run, on that player's world thread, after the attendance record was written.
 */
public final class CalendarAttendedEvent implements IEvent<Void> {

    private final UUID playerId;
    private final String eventId;
    private final int year;
    private final long firedAtMs;

    public CalendarAttendedEvent(@Nonnull UUID playerId, @Nonnull String eventId, int year, long firedAtMs) {
        this.playerId = playerId;
        this.eventId = eventId;
        this.year = year;
        this.firedAtMs = firedAtMs;
    }

    @Nonnull
    public UUID playerId() {
        return playerId;
    }

    /** Which event, lower-cased. */
    @Nonnull
    public String eventId() {
        return eventId;
    }

    /** The year of the run attended: the year it began in. */
    public int year() {
        return year;
    }

    public long firedAtMs() {
        return firedAtMs;
    }
}
