package com.ziggfreed.common.calendar.moon;

/**
 * The engine's moon window, as a world spawn's {@code MoonPhaseRange} reads it ({@code
 * WorldTimeResource.isMoonPhaseWithinRange}): both phases are in it, and a first phase past its last wraps round
 * the cycle, so {@code [4, 1]} of five phases is 4, 0 and 1. Phase 0 is the full moon; the moon wanes through each
 * phase after it and comes back to 0, changing at the game's midday. Pure: the same answer the engine gives, over
 * the phase and phase count the calendar keeps per world.
 */
public final class MoonRange {

    private MoonRange() {
    }

    /**
     * Is {@code phase} inside {@code [min, max]} of a cycle of {@code totalPhases}? Written as the engine writes it:
     * {@code min > max} is {@code min..totalPhases} or {@code 0..max}, otherwise {@code min..max}, every bound in.
     */
    public static boolean within(int phase, int min, int max, int totalPhases) {
        if (min > max) {
            return between(phase, min, totalPhases) || between(phase, 0, max);
        }
        return between(phase, min, max);
    }

    /** Is a range of two phases, each 0 or more, one the engine reads (its spawn validator's own rule)? */
    public static boolean readable(int[] range) {
        return range != null && range.length == 2 && range[0] >= 0 && range[1] >= 0;
    }

    /** {@code MathUtil.within}: both bounds in. */
    private static boolean between(int value, int min, int max) {
        return value >= min && value <= max;
    }
}
