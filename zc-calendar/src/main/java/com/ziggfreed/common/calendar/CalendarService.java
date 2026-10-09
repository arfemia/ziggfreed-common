package com.ziggfreed.common.calendar;

import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Function;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.calendar.asset.CalendarEventAsset;
import com.ziggfreed.common.calendar.asset.CalendarEventConfig;
import com.ziggfreed.common.occurrence.Occurrence;
import com.ziggfreed.common.occurrence.OccurrenceSource;
import com.ziggfreed.common.occurrence.Recurrence;
import com.ziggfreed.common.util.SafeLog;

/**
 * Which run of which event is going on, which have been, and when one next begins: pure over the
 * folded files, the owner's switches and an administrator's forces, with the clock reading passed in.
 *
 * <p><b>Absent beats everything.</b> An event switched off (by the owner's global switch or its own
 * {@code Enabled}) or unable to run ({@linkplain CalendarEventAsset#isReservedId an id another switch
 * uses or an attendance record cannot save}, no readable Window, or no FirstYear from 1970 to 9999) is
 * not enabled, never live, has no history and no next run, whatever a force says. A force then beats
 * the dates. Forced off is never live. Forced on runs the run going on, else the current year's first run
 * not yet over, else its last (never before FirstYear), with that run's own days and number even outside
 * them, so what a forced run earns is filed under that run; a year the window dates no run has nothing to
 * run. {@link #forceOn} takes that one run as it writes the force, and the force keeps it: a run brought
 * forward (or going on) stops when its days end; a run forced again after its days ended (a replay) runs its
 * usual length from the force, or until the event's next run begins by its dates if that comes first. Once
 * the forced run is over every answer is the dates', and no answer writes: only the tick's look clears the
 * spent force ({@link #clearSpentForces}), under {@link #lock()}. Every run is (event, year,
 * number), its number the one its year's rule gives it (a month, a calendar week, a span's place, 1 for a
 * rule with one run a year), so runs are ordered by start, never by number. The next run is the dates'
 * next one after the run going on, or none once a window of per-year days has run out.
 *
 * <p><b>The years and the clock outlive the switches.</b> {@link #firstYear}, {@link #currentYear} and
 * {@link #zone} answer for any loaded event, switched on or not, so what a player earned in a past run
 * keeps its years after the owner switches the event off; so does {@link #dated}, a run's own days by its
 * year and number. A FirstYear outside 1970 to 9999 is no first year at all.
 */
public final class CalendarService implements OccurrenceSource {

    private final CalendarEventConfig config;
    private final CalendarForces forces;
    private final Object lock = new Object();
    /** The events whose answer threw last time they were asked, so the log says so once, not every tick. */
    private final Set<String> failing = ConcurrentHashMap.newKeySet();

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

    /**
     * The force standing on {@code eventId}, or null. A force on whose run is over stands until the tick's next
     * look clears it, though no answer heeds it any more ({@link #forceEndsMs} says whether it is still in force).
     */
    @Nullable
    public Boolean forced(@Nullable String eventId) {
        return eventId == null || eventId.isBlank() ? null : forces.forced(eventId);
    }

    /**
     * Force {@code eventId} on at {@code nowMs}: take the run a force set now runs (the run going on, else the
     * year's first run not yet over, else its last) and write the force pinned to it, a replay when that run's
     * days are already over. Answers that run; null, writing nothing, when the event is absent or its window
     * dates no run that year, so a refusal leaves the standing force (a force off included) as it was. Decided
     * under {@link #lock()}, so the run it takes is never read from half a reload; nothing in it waits for the
     * asset lock or a world thread.
     */
    @Nullable
    public Occurrence forceOn(@Nonnull String eventId, long nowMs) {
        synchronized (lock) {
            CalendarEventAsset event = runnable(eventId);
            Occurrence run = event == null ? null : liveOf(event, nowMs, Boolean.TRUE);
            if (run == null) {
                return null;
            }
            Long replayEndsMs = run.endMs() <= nowMs ? nowMs + (run.endMs() - run.startMs()) : null;
            forces.forceOn(event.getId(), new CalendarForces.Pin(run.year(), run.number(), replayEndsMs));
            return run;
        }
    }

    /**
     * When the force on standing on {@code eventId} stops, if it is still in force at {@code nowMs}: its run's end
     * for a run brought forward, the replay's end for a run forced again. Null when no force on stands, or its
     * run is over (or the dates no longer have it).
     */
    @Nullable
    public Long forceEndsMs(@Nonnull String eventId, long nowMs) {
        CalendarEventAsset event = runnable(eventId);
        CalendarForces.Force force = event == null ? null : forces.standing(event.getId());
        if (force == null || force.pin() == null) {
            return null;
        }
        Long end = forceEnd(event, force.pin());
        return end != null && nowMs < end ? end : null;
    }

    /**
     * Clear every force on whose run is over at {@code nowMs}, or whose run the dates no longer have (an owner
     * took it away). The tick's look calls this under {@link #lock()}, the lock a reload folds under, so half a
     * reload never ends a force; every other reader only answers such an event by its dates. An event switched
     * off or unable to run keeps its force for when it runs again.
     */
    public void clearSpentForces(long nowMs) {
        eachEvent(forces.forcedOn(), id -> {
            CalendarEventAsset event = runnable(id);
            CalendarForces.Force force = event == null ? null : forces.standing(id);
            CalendarForces.Pin pin = force == null ? null : force.pin();
            if (pin != null) {
                Long end = forceEnd(event, pin);
                if (end == null || nowMs >= end) {
                    forces.spend(id, force);
                }
            }
            return null;
        });
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
            // A year's runs come in number order, which need not be their order in time: each is weighed alone.
            for (AnnualWindow.DatedRun run : window.datedRuns(year)) {
                if (run.days().startMs(zone) <= nowMs) {
                    out.add(run.occurrence(event.getId(), zone));
                }
            }
        }
        Occurrence running = liveOf(event, nowMs);
        if (running != null && out.stream().noneMatch(running::sameRun)) {
            out.add(running);
        }
        out.sort(Occurrence.IN_ORDER);
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
        return event == null ? null : yearOf(event, nowMs);
    }

    /** {@link #currentYear} of a loaded {@code event}, resolved once by the caller. */
    private int yearOf(@Nonnull CalendarEventAsset event, long nowMs) {
        Occurrence run = event.canRun() ? liveOf(event, nowMs) : null;
        return run != null ? run.year() : AnnualWindow.yearOf(nowMs, event.zone());
    }

    /**
     * The first run by the event's dates to start after {@code nowMs}, from its FirstYear on, that comes after
     * the run {@link #live} answers (runs compared in time, by start, since a forced-on run keeps its own days):
     * forced on ahead of its dates, that run is going on already and the next is the run after it; forced off,
     * nothing is going on and the next is whenever the dates next come round. Null when the event is absent or
     * no run is left (a window of per-year days that has run out).
     */
    @Override
    @Nullable
    public Occurrence next(@Nonnull String eventId, long nowMs) {
        CalendarEventAsset event = runnable(eventId);
        if (event == null) {
            return null;
        }
        AnnualWindow window = event.annualWindow();
        ZoneId zone = event.zone();
        AnnualWindow.DatedRun next = window.nextRun(nowMs, zone, event.firstYear());
        Occurrence running = liveOf(event, nowMs);
        if (running != null && (next == null || !comesAfter(next, running, zone))) {
            // A forced run may be one whose own days are still ahead: the next is the run after it.
            next = window.after(running.year(), running.number());
        }
        return next == null ? null : next.occurrence(event.getId(), zone);
    }

    /**
     * The run after run {@code number} of {@code year} by the event's dates: that year's next run in time, else
     * the first run of the next year that has one; forces aside. A run the year set aside is followed from its
     * own start, which can come before the kept run it meets, so the answer can be that kept run; a number the
     * year lacks is followed from where its rule would put it. Null when the event is absent or no run is left.
     */
    @Override
    @Nullable
    public Occurrence after(@Nonnull String eventId, int year, int number) {
        CalendarEventAsset event = runnable(eventId);
        if (event == null) {
            return null;
        }
        AnnualWindow.DatedRun next = event.annualWindow().after(year, number);
        return next == null ? null : next.occurrence(event.getId(), event.zone());
    }

    /**
     * Run {@code number} of {@code year} on its days as the loaded event's dates read now, whatever its switches
     * and forces say, so a run switched or forced off keeps its days. Null when no such event is loaded, it cannot
     * run, or the year has no such run (set aside, skipped, before FirstYear or never dated).
     */
    @Override
    @Nullable
    public Occurrence dated(@Nonnull String eventId, int year, int number) {
        CalendarEventAsset event = event(eventId);
        RunDays days = event == null || !event.canRun() ? null : event.annualWindow().run(year, number);
        if (days == null) {
            return null;
        }
        return new AnnualWindow.DatedRun(year, number, days).occurrence(event.getId(), event.zone());
    }

    /**
     * The loaded event's Clock (UTC when it names none or one nobody knows), whatever its switches say;
     * UTC for no such event.
     */
    @Override
    @Nonnull
    public ZoneId zone(@Nonnull String eventId) {
        CalendarEventAsset event = event(eventId);
        return event == null ? ZoneOffset.UTC : event.zone();
    }

    /**
     * Do the loaded event's days move from year to year, or does it come round several times a year, whatever
     * its switches say? False for no such event.
     */
    @Override
    public boolean datesMove(@Nonnull String eventId) {
        CalendarEventAsset event = event(eventId);
        AnnualWindow window = event == null ? null : event.annualWindow();
        return window != null && (window.moves() || window.several());
    }

    /**
     * How the loaded event comes round when it runs monthly or weekly, by the rule dating the year it is in at
     * {@code nowMs} ({@link #currentYear}: a run going on answers the year it started in), whatever its switches say
     * ({@link RecurrenceOf#event}); null for any other event.
     */
    @Override
    @Nullable
    public Recurrence recurrence(@Nonnull String eventId, long nowMs) {
        CalendarEventAsset event = event(eventId);
        return event == null ? null : RecurrenceOf.event(event, yearOf(event, nowMs));
    }

    /**
     * Every event running at {@code nowMs}, by id, in id order. One event that cannot answer costs only itself:
     * it reads as not running, so the tick sees its run end (a real end) and start afresh once it answers again.
     * Its last answer is not carried forward, since a carried run could outlive its own end.
     */
    @Nonnull
    public Map<String, Occurrence> liveAll(long nowMs) {
        return eachEvent(config.ids(), id -> live(id, nowMs));
    }

    /** {@link #eachEvent(List, Function, BiConsumer)}, saying a failure through {@link SafeLog}. */
    @Nonnull
    <T> Map<String, T> eachEvent(@Nonnull List<String> ids, @Nonnull Function<String, T> answer) {
        return eachEvent(ids, answer, (message, cause) -> SafeLog.warn(message, cause));
    }

    /**
     * {@code answer} for each of {@code ids}, in id order, leaving out a null answer. An event whose answer
     * throws is left out too, and said to {@code warn} once until it answers again, so one broken event never
     * stops the others being asked and never fills the log a line a minute.
     */
    @Nonnull
    <T> Map<String, T> eachEvent(@Nonnull List<String> ids, @Nonnull Function<String, T> answer,
            @Nonnull BiConsumer<String, RuntimeException> warn) {
        Map<String, T> out = new TreeMap<>();
        for (String id : ids) {
            T value;
            try {
                value = answer.apply(id);
            } catch (RuntimeException e) {
                if (failing.add(id)) {
                    warn.accept("[calendar] could not answer for event " + id + "; the others still run", e);
                }
                continue;
            }
            failing.remove(id);
            if (value != null) {
                out.put(id, value);
            }
        }
        return out;
    }

    /**
     * Would forcing {@code eventId} on at {@code nowMs} run it? True when it is switched on and a run is going on
     * by its dates or its window dates one in the force's year (the year's next run not yet over, else its last);
     * false when the window dates no run that year (nothing to force on) or the event is absent. A read: it
     * writes no force; {@link #forceOn} decides the same way and writes only when this would answer true.
     */
    public boolean canForceOn(@Nonnull String eventId, long nowMs) {
        CalendarEventAsset event = runnable(eventId);
        return event != null && liveOf(event, nowMs, Boolean.TRUE) != null;
    }

    /** When {@code eventId} next starts by its dates (a force aside), or null when it is absent or no run is left. */
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

    /**
     * The run of a runnable {@code event} going on at {@code nowMs}: its standing force first, then its dates. A
     * force on runs the run it took ({@link #forceOn}) with that run's current days and number, an owner's
     * re-dating followed, until the force's end ({@link #forceEnd}); from then on, or while the dates do not
     * have that run, the dates answer. Pure: it never writes the force, so a reader that catches a reload half
     * done answers wrong for that moment only.
     */
    @Nullable
    private Occurrence liveOf(@Nonnull CalendarEventAsset event, long nowMs) {
        CalendarForces.Force force = forces.standing(event.getId());
        if (force == null) {
            return liveOf(event, nowMs, null);
        }
        CalendarForces.Pin pin = force.pin();
        if (pin == null) {
            return null;
        }
        AnnualWindow window = event.annualWindow();
        ZoneId zone = event.zone();
        RunDays days = window.run(pin.year(), pin.number());
        if (days == null || nowMs >= forceEnd(window, zone, pin, days)) {
            // Over, or not in the dates as they read now: the dates answer; the tick's look clears a spent force.
            return liveOf(event, nowMs, null);
        }
        AnnualWindow.DatedRun dated = window.runContaining(nowMs, zone);
        if (dated != null && dated.year() >= event.firstYear()) {
            return dated.occurrence(event.getId(), zone);
        }
        return new AnnualWindow.DatedRun(pin.year(), pin.number(), days).occurrence(event.getId(), zone);
    }

    /** When the force on {@code event} pinned to {@code pin} ends; null when the dates no longer have its run. */
    @Nullable
    private static Long forceEnd(@Nonnull CalendarEventAsset event, @Nonnull CalendarForces.Pin pin) {
        AnnualWindow window = event.annualWindow();
        RunDays days = window.run(pin.year(), pin.number());
        return days == null ? null : forceEnd(window, event.zone(), pin, days);
    }

    /**
     * When a force on the run {@code days} ends. A run brought forward or going on ends with its days, as they
     * now read. A replay (a run forced again once its days were over) ends its usual length after the force, or
     * when the event's next run begins by its dates if that comes first, so it never comes back after that run.
     */
    private static long forceEnd(@Nonnull AnnualWindow window, @Nonnull ZoneId zone,
            @Nonnull CalendarForces.Pin pin, @Nonnull RunDays days) {
        Long replayEndsMs = pin.replayEndsMs();
        if (replayEndsMs == null) {
            return days.endMs(zone);
        }
        AnnualWindow.DatedRun later = window.after(pin.year(), pin.number());
        return later == null ? replayEndsMs : Math.min(replayEndsMs, later.days().startMs(zone));
    }

    /**
     * The run of a runnable {@code event} going on at {@code nowMs} under {@code forced} (null: none), as if that
     * force were set at {@code nowMs}: the run going on by its dates, else, forced on, the year's first run not yet
     * over, else its last. A read: it takes no run for a standing force.
     */
    @Nullable
    private static Occurrence liveOf(@Nonnull CalendarEventAsset event, long nowMs, @Nullable Boolean forced) {
        if (Boolean.FALSE.equals(forced)) {
            return null;
        }
        AnnualWindow window = event.annualWindow();
        ZoneId zone = event.zone();
        int firstYear = event.firstYear();
        AnnualWindow.DatedRun dated = window.runContaining(nowMs, zone);
        if (dated != null && dated.year() >= firstYear) {
            return dated.occurrence(event.getId(), zone);
        }
        if (Boolean.TRUE.equals(forced)) {
            int forcedYear = Math.max(firstYear, AnnualWindow.yearOf(nowMs, zone));
            // The year's first run not yet over, else its last, with its own days and number; a year the window
            // dates no run has none to run.
            AnnualWindow.DatedRun run = window.forcedRun(forcedYear, nowMs, zone);
            return run == null ? null : run.occurrence(event.getId(), zone);
        }
        return null;
    }

    /** Does {@code run} start after {@code other} does, in {@code zone}? Time order, never run numbers. */
    private static boolean comesAfter(@Nonnull AnnualWindow.DatedRun run, @Nonnull Occurrence other,
            @Nonnull ZoneId zone) {
        return run.days().startMs(zone) > other.startMs();
    }
}
