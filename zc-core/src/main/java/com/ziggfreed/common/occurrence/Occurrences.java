package com.ziggfreed.common.occurrence;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.util.SafeLog;

/**
 * The one slot a module reads recurring events through. The calendar module fills it at library
 * setup; a lower module reads it with no edge to the calendar.
 *
 * <p>Read {@link #source()} at the moment of asking, never into a field: the fill happens at setup,
 * after a class that cached it might have loaded. Unfilled, the slot answers
 * {@link OccurrenceSource#NONE} and says so ONCE, because a gate that silently treats every event as
 * absent is the failure nobody would otherwise see.
 */
public final class Occurrences {

    private static final AtomicReference<OccurrenceSource> SOURCE = new AtomicReference<>();
    private static final AtomicBoolean WARNED_UNFILLED = new AtomicBoolean();
    private static final AtomicReference<LiveSecond> LIVE = new AtomicReference<>();

    private Occurrences() {
    }

    /** Put {@code source} in charge of every answer; null empties the slot again. */
    public static void fill(@Nullable OccurrenceSource source) {
        SOURCE.set(source);
    }

    /** Has anything filled the slot? */
    public static boolean isFilled() {
        return SOURCE.get() != null;
    }

    /** The filled source, or {@link OccurrenceSource#NONE} (reported once) while nothing has filled it. */
    @Nonnull
    public static OccurrenceSource source() {
        OccurrenceSource filled = SOURCE.get();
        if (filled != null) {
            return filled;
        }
        if (WARNED_UNFILLED.compareAndSet(false, true)) {
            SafeLog.warn("[occurrence] a recurring event was asked about before the calendar filled the"
                    + " occurrence slot, so every event reads as absent");
        }
        return OccurrenceSource.NONE;
    }

    /**
     * {@link OccurrenceSource#liveAll} of the source in charge, asked at most once per second of {@code nowMs}:
     * for a hot path that asks on every write (a stat counted on each block broken), so a burst of writes walks
     * the events once. An answer is a second old at most, so a force set or ended mid-second shows a moment
     * late; a run by its dates starts and ends on a whole second, so its turn is never missed. A source that
     * throws caches nothing and the throw reaches the caller. Unmodifiable, in the source's own order.
     */
    @Nonnull
    public static Map<String, Occurrence> liveAllCached(long nowMs) {
        OccurrenceSource source = source();
        long second = Math.floorDiv(nowMs, 1000L);
        LiveSecond held = LIVE.get();
        if (held != null && held.source() == source && held.second() == second) {
            return held.live();
        }
        Map<String, Occurrence> live = Collections.unmodifiableMap(new LinkedHashMap<>(source.liveAll(nowMs)));
        LIVE.set(new LiveSecond(source, second, live));
        return live;
    }

    /** Empty the slot and the once-only report; for a test starting from nothing. */
    public static void resetForTests() {
        SOURCE.set(null);
        WARNED_UNFILLED.set(false);
        LIVE.set(null);
    }

    /** One second's {@link #liveAllCached} answer, and the source that gave it. */
    private record LiveSecond(@Nonnull OccurrenceSource source, long second, @Nonnull Map<String, Occurrence> live) {
    }
}
