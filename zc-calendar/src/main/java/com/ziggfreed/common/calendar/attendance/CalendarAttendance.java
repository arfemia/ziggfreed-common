package com.ziggfreed.common.calendar.attendance;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import javax.annotation.Nonnull;

import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.event.events.player.PlayerReadyEvent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.calendar.CalendarHerald;
import com.ziggfreed.common.calendar.CalendarRuntime;
import com.ziggfreed.common.calendar.CalendarService;
import com.ziggfreed.common.calendar.PlayerWorldThread;
import com.ziggfreed.common.calendar.event.CalendarEvents;
import com.ziggfreed.common.calendar.tick.CalendarTick;
import com.ziggfreed.common.occurrence.Occurrence;
import com.ziggfreed.common.util.SafeLog;

/**
 * A player is present for a run when they enter a world while it runs ({@code PlayerReadyEvent}) or are
 * online when it really begins (a tick's non-resumed start). Each is credited once per run (each run of a
 * year, by its number) on the player's own world thread: the record is written, {@code CalendarAttendedEvent}
 * fires for each run at once, and the runs' start banners show, queued a gap apart, after the tick's end banners
 * when a run's start credits everyone online ({@link CalendarHerald#showStarts}). A boot catch-up credits
 * nobody, since nobody was here.
 */
public final class CalendarAttendance {

    private CalendarAttendance() {
    }

    /** The runs a tick began for real, which everyone already online is present for. */
    @Nonnull
    static List<Occurrence> freshStarts(@Nonnull CalendarTick tick) {
        List<Occurrence> out = new ArrayList<>();
        for (CalendarTick.Started start : tick.started()) {
            if (!start.resumed()) {
                out.add(start.occurrence());
            }
        }
        return out;
    }

    /** Credit {@code record} with each run it has not attended yet; the newly credited runs, in order. */
    @Nonnull
    static List<Occurrence> credit(@Nonnull CalendarAttendanceComponent record, @Nonnull Collection<Occurrence> runs) {
        List<Occurrence> fresh = new ArrayList<>();
        for (Occurrence run : runs) {
            if (record.markAttended(run.eventId(), run.year(), run.number())) {
                fresh.add(run);
            }
        }
        return fresh;
    }

    /**
     * The runs going on at {@code nowMs}, read under {@link CalendarService#lock()}, the lock every reload
     * folds under: a fold empties a layer before it fills it again, and a credit read in between (an event
     * the owner switched off, read from its pack file as on) would be written for good.
     */
    @Nonnull
    static List<Occurrence> runningAt(long nowMs) {
        CalendarService service = CalendarRuntime.service();
        synchronized (service.lock()) {
            return List.copyOf(service.liveAll(nowMs).values());
        }
    }

    /** {@code PlayerReadyEvent}: entering any world while runs go on is being present for them. */
    public static void onPlayerReady(@Nonnull PlayerReadyEvent event) {
        try {
            long now = CalendarRuntime.now();
            List<Occurrence> live = runningAt(now);
            if (live.isEmpty()) {
                return;
            }
            hop(event.getPlayerRef(), live, now, 0L);
        } catch (Throwable t) {
            SafeLog.warn("[calendar] attendance on player ready failed", t);
        }
    }

    /** A tick listener: a run that just began credits everyone already online. */
    public static void onTick(@Nonnull CalendarTick tick) {
        List<Occurrence> fresh = freshStarts(tick);
        if (fresh.isEmpty()) {
            return;
        }
        try {
            // One tick is one queue: the start banners these credits owe wait for the tick's end banners.
            long afterMs = CalendarHerald.startsAfterMs(tick, CalendarRuntime.service()::event);
            for (PlayerRef player : Universe.get().getPlayers()) {
                Ref<EntityStore> ref = player == null ? null : player.getReference();
                if (ref != null) {
                    hop(ref, fresh, tick.nowMs(), afterMs);
                }
            }
        } catch (Throwable t) {
            SafeLog.warn("[calendar] attendance at a run's start failed", t);
        }
    }

    private static void hop(@Nonnull Ref<EntityStore> ref, @Nonnull List<Occurrence> runs, long nowMs, long afterMs) {
        PlayerWorldThread.queue(ref, () -> creditOnWorldThread(ref, runs, nowMs, afterMs));
    }

    private static void creditOnWorldThread(@Nonnull Ref<EntityStore> ref, @Nonnull List<Occurrence> runs, long nowMs,
            long afterMs) {
        try {
            ComponentType<EntityStore, CalendarAttendanceComponent> type = CalendarAttendanceComponent.TYPE;
            if (type == null || !ref.isValid()) {
                return;
            }
            Store<EntityStore> store = ref.getStore();
            CalendarAttendanceComponent record = store.getComponent(ref, type);
            PlayerRef player = store.getComponent(ref, PlayerRef.getComponentType());
            if (record == null || player == null) {
                return;
            }
            List<Occurrence> started = credit(record, runs);
            for (Occurrence run : started) {
                CalendarEvents.fireAttended(player.getUuid(), run, nowMs);
            }
            CalendarHerald.showStarts(player, started, afterMs);
        } catch (Throwable t) {
            SafeLog.warn("[calendar] could not credit attendance", t);
        }
    }
}
