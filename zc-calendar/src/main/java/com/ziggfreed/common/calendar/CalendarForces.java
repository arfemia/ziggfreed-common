package com.ziggfreed.common.calendar;

import java.util.List;
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
 * <p>A force on runs ONE run, taken as the force is written ({@link CalendarService#forceOn}, a {@link Pin}),
 * so it never walks on to another. Once that run is over a reader answers by the dates without writing here,
 * and only the tick's look, under the calendar's lock, clears the spent force ({@link #spend}). A force off
 * stands until cleared.
 */
public final class CalendarForces {

    private static final CalendarForces INSTANCE = new CalendarForces();

    /**
     * The run a force on took: run {@code number} of {@code year}. {@code replayEndsMs} is set when that run's
     * days were already over as it was forced (a replay): the replay then ends its usual length after the force.
     * Null for a run brought forward or going on, which ends when its own days do. Decided once, as the force is
     * written, so an owner moving the run's days later never turns one kind into the other.
     */
    record Pin(int year, int number, @Nullable Long replayEndsMs) {
    }

    /** One force as it stands: on with the run it took, or off. Compared by identity. */
    static final class Force {

        @Nullable private final Pin pin;

        private Force(@Nullable Pin pin) {
            this.pin = pin;
        }

        /** Forced on (it has a run), rather than off. */
        boolean running() {
            return pin != null;
        }

        /** The run a force on took; null for a force off. */
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

    /** Stop {@code eventId} until cleared. */
    public void forceOff(@Nonnull String eventId) {
        forced.put(normalize(eventId), new Force(null));
    }

    /** Hand {@code eventId} back to its dates. */
    public void clear(@Nonnull String eventId) {
        forced.remove(normalize(eventId));
    }

    /** The force standing on {@code eventId} (true: on, false: off), or null when it follows its dates. */
    @Nullable
    public Boolean forced(@Nonnull String eventId) {
        Force force = forced.get(normalize(eventId));
        return force == null ? null : force.running();
    }

    /** Hand every event back to its dates. */
    public void clearAll() {
        forced.clear();
    }

    /** Run {@code eventId}'s run {@code pin} until it is over or cleared. Only {@link CalendarService#forceOn} writes this. */
    void forceOn(@Nonnull String eventId, @Nonnull Pin pin) {
        forced.put(normalize(eventId), new Force(pin));
    }

    /** The force standing on {@code eventId} as it stands, or null. */
    @Nullable
    Force standing(@Nonnull String eventId) {
        return forced.get(normalize(eventId));
    }

    /** Every event forced on, lower-cased. */
    @Nonnull
    List<String> forcedOn() {
        return forced.entrySet().stream().filter(entry -> entry.getValue().running()).map(Map.Entry::getKey)
                .sorted().toList();
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
