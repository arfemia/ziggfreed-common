package com.ziggfreed.common.calendar.moon;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * What the calendar knows of each world's moon: its phase and how many phases its cycle has. Every answer is a
 * read of what the world last said, safe and cheap from any thread, so a gate may ask on every read. A seam so the
 * calendar's answers are tested without an engine.
 */
@FunctionalInterface
public interface MoonSource {

    /** Knows no world: every moon event waits. What a unit test without moons answers. */
    MoonSource NONE = world -> null;

    /**
     * The moon of {@code world} (by name, any case), or of the server's default world when it is null or blank; null
     * when that world is not loaded or has not said its phase yet.
     */
    @Nullable
    Reading reading(@Nullable String world);

    /**
     * Whether {@code world} (null or blank: the default world) has its game time paused, so its moon keeps its phase;
     * false when it is not loaded or this source cannot tell.
     */
    default boolean paused(@Nullable String world) {
        return false;
    }

    /** The name {@code world} stands for: itself trimmed, or the default world's for a null or blank one (null if none). */
    @Nullable
    default String name(@Nullable String world) {
        return world == null || world.isBlank() ? null : world.trim();
    }

    /**
     * A world's moon as it last changed.
     *
     * @param world       the world's name as the engine spells it
     * @param phase       0 is the full moon
     * @param totalPhases how many phases the world's cycle has (its gameplay config's {@code TotalMoonPhases})
     */
    record Reading(@Nonnull String world, int phase, int totalPhases) {

        /** Is this moon inside {@code [min, max]}, by the engine's own rule ({@link MoonRange#within})? */
        public boolean within(int min, int max) {
            return MoonRange.within(phase, min, max, totalPhases);
        }
    }
}
