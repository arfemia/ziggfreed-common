package com.ziggfreed.common.occurrence;

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

    /** Empty the slot and the once-only report; for a test starting from nothing. */
    public static void resetForTests() {
        SOURCE.set(null);
        WARNED_UNFILLED.set(false);
    }
}
