package com.ziggfreed.common.calendar.tick;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.calendar.CalendarService;
import com.ziggfreed.common.calendar.event.CalendarEvents;
import com.ziggfreed.common.occurrence.Occurrence;
import com.ziggfreed.common.util.PeriodMath;
import com.ziggfreed.common.util.SafeLog;

/**
 * The calendar's once-a-minute look at the clock: what began and what ended since the last look, told
 * as native events and handed to every listener (spawns, attendance, the herald).
 *
 * <p>Nothing looks before {@link #start()}, which the bootstrap calls on {@code BootEvent}: its first look
 * is the boot's (every run already going is a RESUMED start), and a world added during the universe's own
 * start never sees a calendar write. A change of content or of a switch asks for a look soon through
 * {@link #requestEvaluation()}. The look reads the calendar under {@link CalendarService#lock()}, the lock
 * a reload folds both layers under, so a tick never sees half a reload, and there it clears every force whose
 * run is over (the only place one is cleared but a command); events and listeners then run outside that lock,
 * on the tick's own thread.
 */
public final class CalendarTicker {

    /** How often the calendar looks at the clock. */
    public static final long PERIOD_MS = PeriodMath.MINUTE_MS;

    private final CalendarService service;
    private final TickScheduler scheduler;
    private final LongSupplier clock;
    private final List<Consumer<CalendarTick>> listeners = new CopyOnWriteArrayList<>();
    /** The runs going on at the last look, by event id. */
    private final Map<String, Occurrence> live = new TreeMap<>();
    private volatile boolean started;
    @Nullable
    private TickScheduler.Cancellable repeating;

    public CalendarTicker(@Nonnull CalendarService service, @Nonnull TickScheduler scheduler,
            @Nonnull LongSupplier clock) {
        this.service = service;
        this.scheduler = scheduler;
        this.clock = clock;
    }

    /** Hear every look. A listener that throws costs only itself. */
    public void listen(@Nonnull Consumer<CalendarTick> listener) {
        listeners.add(listener);
    }

    /** The boot's look, then one every {@link #PERIOD_MS}. A second call does nothing. */
    public synchronized void start() {
        if (started) {
            return;
        }
        started = true;
        evaluate(true);
        repeating = scheduler.repeat(() -> evaluate(false), PERIOD_MS, PERIOD_MS);
    }

    /**
     * Look again soon; nothing before boot. Never takes the ticker's lock: a content load asks from inside
     * the engine's asset write lock, which the look in progress may be waiting for (a listener writing
     * spawn rules), so waiting here would leave each thread waiting for the other.
     */
    public void requestEvaluation() {
        if (!isStarted()) {
            return;
        }
        scheduler.runSoon(() -> evaluate(false));
    }

    /** Read without the ticker's lock, which {@link #requestEvaluation()} relies on. */
    public boolean isStarted() {
        return started;
    }

    /** Stop looking. */
    public synchronized void stop() {
        if (repeating != null) {
            repeating.cancel();
        }
        repeating = null;
        started = false;
    }

    synchronized CalendarTick evaluate(boolean booting) {
        long now = clock.getAsLong();
        CalendarTick tick;
        synchronized (service.lock()) {
            // The one writer of a spent force: under the lock a reload folds under, so never on half a reload.
            service.clearSpentForces(now);
            Map<String, Occurrence> current = service.liveAll(now);
            tick = CalendarTransitions.diff(live, current, service::isEnabled, now, booting);
            live.clear();
            live.putAll(current);
        }
        for (CalendarTick.Ended ended : tick.ended()) {
            CalendarEvents.fireEnded(ended.occurrence(), ended.switchedOff(), now);
            SafeLog.info("[calendar] " + ended.occurrence().eventId() + " " + ended.occurrence().year()
                    + (ended.switchedOff() ? " was switched off" : " ended"));
        }
        for (CalendarTick.Started start : tick.started()) {
            CalendarEvents.fireStarted(start.occurrence(), start.resumed(), now);
            SafeLog.info("[calendar] " + start.occurrence().eventId() + " " + start.occurrence().year()
                    + (start.resumed() ? " is running (resumed at boot)" : " started"));
        }
        for (Consumer<CalendarTick> listener : listeners) {
            try {
                listener.accept(tick);
            } catch (Throwable t) {
                SafeLog.warn("[calendar] a calendar tick listener failed", t);
            }
        }
        return tick;
    }
}
