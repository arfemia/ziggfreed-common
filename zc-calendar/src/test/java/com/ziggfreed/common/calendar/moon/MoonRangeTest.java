package com.ziggfreed.common.calendar.moon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * The engine's moon window as the calendar reads it ({@code WorldTimeResource.isMoonPhaseWithinRange}): both phases
 * in, and a first phase past its last wrapping round the cycle.
 */
class MoonRangeTest {

    private static final int VANILLA_PHASES = 5;

    /** The phases of a {@code total}-phase cycle inside {@code [min, max]}. */
    private static List<Integer> inside(int min, int max, int total) {
        List<Integer> out = new ArrayList<>();
        for (int phase = 0; phase < total; phase++) {
            if (MoonRange.within(phase, min, max, total)) {
                out.add(phase);
            }
        }
        return out;
    }

    @Test
    void theFullMoonWindowIsTheFullNightAndTheOneAfter() {
        assertEquals(List.of(0, 1), inside(0, 1, VANILLA_PHASES), "[0, 1]: full, then the night after");
        assertEquals(List.of(0), inside(0, 0, VANILLA_PHASES), "[0, 0]: the full moon alone");
    }

    @Test
    void aFirstPhasePastItsLastWrapsRoundTheCycle() {
        assertEquals(List.of(0, 1, 4), inside(4, 1, VANILLA_PHASES), "[4, 1]: the night before, full, the night after");
        assertEquals(List.of(0, 3, 4), inside(3, 0, VANILLA_PHASES));
        assertEquals(List.of(1, 2, 3), inside(1, 3, VANILLA_PHASES), "no wrap when the first comes first");
    }

    @Test
    void aRangePastTheCycleReadsAsTheEngineReadsIt() {
        assertEquals(List.of(0, 1, 2, 3, 4), inside(0, 9, VANILLA_PHASES), "a last past the cycle takes every phase");
        assertEquals(List.of(), inside(7, 9, VANILLA_PHASES), "phases the cycle never reaches never come");
    }

    @Test
    void aReadableRangeIsTwoPhasesOfZeroOrMore() {
        assertTrue(MoonRange.readable(new int[]{0, 1}));
        assertTrue(MoonRange.readable(new int[]{4, 1}));
        assertFalse(MoonRange.readable(null));
        assertFalse(MoonRange.readable(new int[]{0}));
        assertFalse(MoonRange.readable(new int[]{0, 1, 2}));
        assertFalse(MoonRange.readable(new int[]{-1, 1}), "the engine's spawn validator refuses a negative phase");
    }
}
