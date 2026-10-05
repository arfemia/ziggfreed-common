package com.ziggfreed.common.encounter.run;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import com.ziggfreed.common.encounter.run.AddScaling.Step;

/**
 * What becomes of one add on its encounter's tick, on the pure question behind it (a unit JVM cannot
 * stand up the tick or a store): scaled once its health is built, let go when the run scales no add,
 * when it is gone, or when it is the subject, and never left waiting for ever.
 */
class AddScalingTest {

    @Test
    void anAddWhoseHealthIsBuiltIsScaledNow() {
        assertEquals(Step.APPLY, AddScaling.step(true, true, false, true, 0));
        assertEquals(Step.APPLY, AddScaling.step(true, true, false, true, 7), "however long it waited");
    }

    @Test
    void theSubjectIsNeverScaledAsAnAdd() {
        assertEquals(Step.DROP, AddScaling.step(true, true, true, true, 0),
                "the boss rises from the same spawners and carries the same lineage");
    }

    @Test
    void aRunThatScalesNoAddLetsEveryAddGo() {
        assertEquals(Step.DROP, AddScaling.step(false, true, false, true, 0),
                "no Scale.Adds, a settled run, or a factor of 1 leaves the add as its role made it");
    }

    @Test
    void anAddGoneFromTheWorldIsForgotten() {
        assertEquals(Step.DROP, AddScaling.step(true, false, false, false, 0));
    }

    @Test
    void anAddWaitsForItsHealthButNotForEver() {
        assertEquals(Step.WAIT, AddScaling.step(true, true, false, false, 0));
        assertEquals(Step.WAIT, AddScaling.step(true, true, false, false, AddScaling.MAX_WAIT_TICKS - 2));
        assertEquals(Step.DROP, AddScaling.step(true, true, false, false, AddScaling.MAX_WAIT_TICKS - 1),
                "after MAX_WAIT_TICKS ticks without a health stat it is let go unscaled");
    }
}
