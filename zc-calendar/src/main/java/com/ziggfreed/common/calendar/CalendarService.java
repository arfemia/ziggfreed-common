package com.ziggfreed.common.calendar;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.calendar.asset.CalendarEventAsset;
import com.ziggfreed.common.calendar.asset.CalendarEventConfig;
import com.ziggfreed.common.occurrence.Occurrence;
import com.ziggfreed.common.occurrence.OccurrenceSource;

/**
 * Which run of which event is going on, which have been, and when one next begins: pure over the
 * folded files, the owner's switches and an administrator's forces, with the clock reading passed in.
 *
 * <p><b>Absent beats everything.</b> An event switched off (by the owner's global switch or its own
 * {@code Enabled}) or unable to run ({@linkplain CalendarEventAsset#isReservedId an id another switch
 * uses or an attendance record cannot save}, no readable Window, or no FirstYear from 1970 to 9999) is
 * not enabled, never live and has no history, whatever a force says. A force then beats the dates:
 * forced off is never live; forced on runs the run of the current year (never before FirstYear) even
 * outside its dates, so what a forced run earns is filed under that year.
 *
 * <p><b>The years outlive the switches.</b> {@link #firstYear} and {@link #currentYear} answer for any
 * loaded event, switched on or not, so what a player earned in a past run keeps its years after the
 * owner switches the event off. A FirstYear outside 1970 to 9999 is no first year at all.
 */
public final class CalendarService implements OccurrenceSource {

    private final CalendarEventConfig config;
    private final CalendarForces forces;
    private final Object lock = new Object();

    public CalendarService(@Nonnull CalendarEventConfig config, @Nonnull CalendarForces forces) {
        this.config = config;
        this.forces = forces;
    }

    /**
     * The monitor a content reload and the tick's look share, so a tick never sees half a reload. A world
     * thread waits for it while holding the engine's asset read lock (a player's attendance read), so
     * nothing done while holding it may wait for the asset lock or for a world thread.
     */
    @Nonnull
    public Object lock() {
        return lock;
    }

    /** Is a file of this id loaded at all, switched on or not? */
    public boolean isLoaded(@Nullable String eventId) {
        return event(eventId) != null;
    }

    /** The event's folded file, or null. */
    @Nullable
    public CalendarEventAsset event(@Nullable String eventId) {
        return eventId == null || eventId.isBlank() ? null : config.resolve(eventId.trim());
    }

    /** Every loaded event id, lower-cased and sorted. */
    @Nonnull
    public List<String> eventIds() {
        return config.ids();
    }

    /** The owner's switch over every event. */
    public boolean isGloballyEnabled() {
        return config.isGlobalEnabled();
    }

    /** The force standing on {@code eventId}, or null. */
    @Nullable
    public Boolean forced(@Nullable String eventId) {
        return eventId == null || eventId.isBlank() ? null : forces.forced(eventId);
    }

    @Override
    public boolean isEnabled(@Nonnull String eventId) {
        return runnable(eventId) != null;
    }

    @Override
    @Nullable
    public Occurrence live(@Nonnull String eventId, long nowMs) {
        CalendarEventAsset event = runnable(eventId);
        return event == null ? null : liveOf(event, nowMs);
    }

    @Override
    @Nonnull
    public List<Occurrence> history(@Nonnull String eventId, long nowMs) {
        CalendarEventAsset event = runnable(eventId);
        if (event == null) {
            return List.of();
        }
        AnnualWindow window = event.annualWindow();
        ZoneId zone = event.zone();
        List<Occurrence> out = new ArrayList<>();
        int lastYear = AnnualWindow.yearOf(nowMs, zone);
        for (int year = event.firstYear(); year <= lastYear; year++) {
            if (window.startMs(year, zone) <= nowMs) {
                out.add(occurrence(event, window, zone, year));
            }
        }
        Occurrence running = liveOf(event, nowMs);
        if (running != null && out.stream().noneMatch(run -> run.year() == running.year())) {
            out.add(running);
            out.sort(Comparator.comparingInt(Occurrence::year));
        }
        return List.copyOf(out);
    }

    /**
     * The loaded file's FirstYear, whatever the switches say; null when none is loaded, it states none, or it
     * states one outside 1970 to 9999 (its {@code FIRST_YEAR_OUT_OF_RANGE} problem): such an event never runs,
     * so a reader keeping a copy per year has no year to count from.
     */
    @Override
    @Nullable
    public Integer firstYear(@Nonnull String eventId) {
        CalendarEventAsset event = event(eventId);
        return event == null || event.problems().contains(CalendarEventAsset.PROBLEM_FIRST_YEAR_OUT_OF_RANGE)
                ? null : event.firstYear();
    }

    /**
     * The year the loaded event is in, whatever the switches say: the run {@link #live} would answer were
     * it switched on (its dates, or a force), else the calendar year in its own clock; null when none is
     * loaded. An event that cannot run has no run, so it answers the calendar year.
     */
    @Override
    @Nullable
    public Integer currentYear(@Nonnull String eventId, long nowMs) {
        CalendarEventAsset event = event(eventId);
        if (event == null) {
            return null;
        }
        Occurrence run = event.canRun() ? liveOf(event, nowMs) : null;
        return run != null ? run.year() : AnnualWindow.yearOf(nowMs, event.zone());
    }

    /** Every event running at {@code nowMs}, by id, in id order. */
    @Nonnull
    public Map<String, Occurrence> liveAll(long nowMs) {
        Map<String, Occurrence> out = new TreeMap<>();
        for (String id : config.ids()) {
            Occurrence run = live(id, nowMs);
            if (run != null) {
                out.put(id, run);
            }
        }
        return out;
    }

    /** When {@code eventId} next starts by its dates (a force aside), or null when it is absent. */
    @Nullable
    public Long nextStartMs(@Nonnull String eventId, long nowMs) {
        CalendarEventAsset event = runnable(eventId);
        return event == null ? null : event.annualWindow().nextStartMs(nowMs, event.zone(), event.firstYear());
    }

    /**
     * The event's file when it is switched on (globally and in itself) and can run, else null. Resolved
     * ONCE per call, so a reload landing mid-call can never pair one file's switch with another's dates.
     */
    @Nullable
    private CalendarEventAsset runnable(@Nullable String eventId) {
        CalendarEventAsset event = event(eventId);
        return event != null && config.isGlobalEnabled() && event.isEnabled() && event.canRun() ? event : null;
    }

    /** The run of a runnable {@code event} going on at {@code nowMs}: a force first, then its dates. */
    @Nullable
    private Occurrence liveOf(@Nonnull CalendarEventAsset event, long nowMs) {
        Boolean forced = forces.forced(event.getId());
        if (Boolean.FALSE.equals(forced)) {
            return null;
        }
        AnnualWindow window = event.annualWindow();
        ZoneId zone = event.zone();
        int firstYear = event.firstYear();
        Integer year = window.yearContaining(nowMs, zone);
        if (year != null && year >= firstYear) {
            return occurrence(event, window, zone, year);
        }
        if (Boolean.TRUE.equals(forced)) {
            return occurrence(event, window, zone, Math.max(firstYear, AnnualWindow.yearOf(nowMs, zone)));
        }
        return null;
    }

    @Nonnull
    private static Occurrence occurrence(@Nonnull CalendarEventAsset event, @Nonnull AnnualWindow window,
            @Nonnull ZoneId zone, int year) {
        return new Occurrence(event.getId(), year, window.startMs(year, zone), window.endMs(year, zone));
    }
}
