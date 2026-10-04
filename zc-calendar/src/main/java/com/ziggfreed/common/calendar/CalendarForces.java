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
 */
public final class CalendarForces {

    private static final CalendarForces INSTANCE = new CalendarForces();

    private final Map<String, Boolean> forced = new ConcurrentHashMap<>();

    private CalendarForces() {
    }

    @Nonnull
    public static CalendarForces getInstance() {
        return INSTANCE;
    }

    /** Run ({@code true}) or stop ({@code false}) {@code eventId} until cleared. */
    public void force(@Nonnull String eventId, boolean running) {
        forced.put(normalize(eventId), running);
    }

    /** Hand {@code eventId} back to its dates. */
    public void clear(@Nonnull String eventId) {
        forced.remove(normalize(eventId));
    }

    /** The force standing on {@code eventId}, or null when it follows its dates. */
    @Nullable
    public Boolean forced(@Nonnull String eventId) {
        return forced.get(normalize(eventId));
    }

    /** Hand every event back to its dates. */
    public void clearAll() {
        forced.clear();
    }

    @Nonnull
    private static String normalize(@Nonnull String eventId) {
        return eventId.trim().toLowerCase(Locale.ROOT);
    }
}
