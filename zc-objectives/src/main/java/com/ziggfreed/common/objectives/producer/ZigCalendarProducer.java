package com.ziggfreed.common.objectives.producer;

import java.util.UUID;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.plugin.PluginBase;
import com.ziggfreed.common.calendar.event.CalendarAttendedEvent;
import com.ziggfreed.common.util.SafeLog;

/**
 * Turns a calendar attendance into quest and achievement progress: {@code CALENDAR_ATTENDED} once per
 * player per run of a calendar event.
 *
 * <p>An EVENT-BUS listener like {@link ZigEncounterProducer}: the calendar names the player by uuid, so the
 * moment is fed on that player's own world thread through {@link PlayerMomentDispatch}. The contract content
 * sees: {@code Target} is the calendar event id, {@code Qualifier} is the run's year as text (a step without
 * one counts any year), {@code Amount} is 1 per run.
 */
public final class ZigCalendarProducer {

    /** Fired once per player per run of a calendar event. */
    public static final String KIND = "CALENDAR_ATTENDED";

    private static final long AMOUNT = 1L;

    private static final String LABEL = "calendar";

    private ZigCalendarProducer() {
    }

    /** Listen for attendance on the shared bus. Registration only, from {@code ProgressionDefaults.install}. */
    public static void install(@Nonnull PluginBase plugin) {
        plugin.getEventRegistry().registerGlobal(CalendarAttendedEvent.class, ZigCalendarProducer::onAttended);
    }

    /** Where one player's moment goes. A seam purely so the fan-out needs no server to test. */
    @FunctionalInterface
    interface AttendanceSink {

        void accept(@Nonnull UUID playerId, @Nonnull String kindId, @Nonnull String target, @Nullable String qualifier);
    }

    static void onAttended(@Nonnull CalendarAttendedEvent event) {
        try {
            fanOut(event, ZigCalendarProducer::dispatch);
        } catch (Throwable t) {
            SafeLog.warn("[progression] calendar attendance progress failed", t);
        }
    }

    static void fanOut(@Nonnull CalendarAttendedEvent event, @Nonnull AttendanceSink sink) {
        sink.accept(event.playerId(), KIND, event.eventId(), Integer.toString(event.year()));
    }

    private static void dispatch(@Nonnull UUID playerId, @Nonnull String kindId, @Nonnull String target,
            @Nullable String qualifier) {
        PlayerMomentDispatch.fire(LABEL, playerId, kindId, target, qualifier, AMOUNT, null);
    }
}
