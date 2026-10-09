package com.ziggfreed.common.calendar.event;

import java.util.UUID;

import javax.annotation.Nonnull;

import com.hypixel.hytale.event.IEvent;

/**
 * A player was on the server during a run of a calendar event for the first time that run: fired once
 * per player per run, on that player's world thread, after the attendance record was written. Several runs
 * of one year differ by {@link #number()}.
 */
public final class CalendarAttendedEvent implements IEvent<Void> {

    private final UUID playerId;
    private final String eventId;
    private final int year;
    private final int number;
    private final long firedAtMs;

    /** The year's run 1: what an event that comes round once a year has. */
    public CalendarAttendedEvent(@Nonnull UUID playerId, @Nonnull String eventId, int year, long firedAtMs) {
        this(playerId, eventId, year, 1, firedAtMs);
    }

    public CalendarAttendedEvent(@Nonnull UUID playerId, @Nonnull String eventId, int year, int number,
            long firedAtMs) {
        this.playerId = playerId;
        this.eventId = eventId;
        this.year = year;
        this.number = number;
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

    /**
     * The run's number within its year, from 1: the event's dates name it (a month, a calendar week, a span's
     * place), so it is a name, never a count, and a year's numbers may skip. An event that comes round once a
     * year only has run 1.
     */
    public int number() {
        return number;
    }

    public long firedAtMs() {
        return firedAtMs;
    }
}
