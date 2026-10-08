package com.ziggfreed.common.calendar;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * An administrator's override of an event's dates: forced on (running whatever its dates say) or forced
 * off (stopped whatever its dates say). Held in memory only, so a restart hands every event back to its
 * dates; the owner's switches always win over a force.
 *
 * <p>A force on runs ONE run. It takes that run the first time the calendar reads it ({@link Pin}) and keeps
 * it, so it never walks on to another, and the calendar clears it once that run is over ({@link #spend}). A
 * force off stands until cleared.
 */
public final class CalendarForces {

    private static final CalendarForces INSTANCE = new CalendarForces();

    /**
     * The run a force on took: run {@code number} of {@code year}, taken at {@code sinceMs} (the clock reading
     * of the first look under the force), so the calendar can tell a run brought forward from one run again.
     */
    record Pin(int year, int number, long sinceMs) {
    }

    /** One force as it stands: on or off and, once read, the run a force on took. Compared by identity. */
    static final class Force {

        private final boolean running;
        @Nullable private final Pin pin;

        private Force(boolean running, @Nullable Pin pin) {
            this.running = running;
            this.pin = pin;
        }

        boolean running() {
            return running;
        }

        @Nullable
        Pin pin() {
            return pin;
        }
    }

    private final Map<String, Force> forced = new ConcurrentHashMap<>();

    private CalendarForces() {
    }

    @Nonnull
    public static CalendarForces getInstance() {
        return INSTANCE;
    }

    /**
     * Run ({@code true}) or stop ({@code false}) {@code eventId}: stopped until cleared; running until cleared, or
     * until the run the force takes is over.
     */
    public void force(@Nonnull String eventId, boolean running) {
        forced.put(normalize(eventId), new Force(running, null));
    }

    /** Hand {@code eventId} back to its dates. */
    public void clear(@Nonnull String eventId) {
        forced.remove(normalize(eventId));
    }

    /** The force standing on {@code eventId}, or null when it follows its dates. */
    @Nullable
    public Boolean forced(@Nonnull String eventId) {
        Force force = forced.get(normalize(eventId));
        return force == null ? null : force.running();
    }

    /** Hand every event back to its dates. */
    public void clearAll() {
        forced.clear();
    }

    /** The force standing on {@code eventId} as it stands, or null. */
    @Nullable
    Force standing(@Nonnull String eventId) {
        return forced.get(normalize(eventId));
    }

    /**
     * Give the force on {@code eventId} that is still {@code unpinned} the run {@code pin}, and answer the force
     * standing after: the pinned one, or whatever another reader or a command put there first.
     */
    @Nullable
    Force pin(@Nonnull String eventId, @Nonnull Force unpinned, @Nonnull Pin pin) {
        return forced.computeIfPresent(normalize(eventId),
                (id, standing) -> standing == unpinned ? new Force(unpinned.running(), pin) : standing);
    }

    /** The run {@code spent} took is over: clear it, unless a newer force has replaced it since. */
    void spend(@Nonnull String eventId, @Nonnull Force spent) {
        forced.remove(normalize(eventId), spent);
    }

    @Nonnull
    private static String normalize(@Nonnull String eventId) {
        return eventId.trim().toLowerCase(Locale.ROOT);
    }
}
