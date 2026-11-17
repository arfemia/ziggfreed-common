package com.ziggfreed.common.encounter.seam;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.joml.Vector3d;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.ziggfreed.common.encounter.run.EncounterScaling;

/**
 * A run's step on a fight (R1053): a tier only ever makes a fight harder, its damage reads as the whole percent the
 * outgoing pass adds, its health multiplies the party scale, and a fight in no run, or with the seam unfilled, takes
 * none. Untagged: nothing here builds an engine item.
 */
class RunScaleTest {

    @AfterEach
    void reset() {
        EncounterSeams.resetForTests();
    }

    @Test
    void aStepOnlyEverMakesAFightHarder() {
        RunScale tier3 = new RunScale(2.4, 1.6);
        assertEquals(60, tier3.damagePercent());
        assertFalse(tier3.none());
        assertTrue(new RunScale(0.5, Double.NaN).none(), "a step below 1, or none at all, is no step");
        assertEquals(30, new RunScale(1.6, 1.3).damagePercent());
    }

    @Test
    void aTiersHealthMultipliesThePartyScaleUnderTheCeiling() {
        double solo = EncounterScaling.factor(null, 1, 0.0, 1.6);
        assertEquals(1.6, solo, 1e-9, "a tier's step alone raises a solo boss");
        double fiveAtTier3 = EncounterScaling.factor(null, 5, 0.0, 2.4);
        assertEquals(2.4, fiveAtTier3, 1e-9, "with no party scale written, only the step");
    }

    @Test
    void aFightInNoRunOrWithNoFillTakesNoStep() {
        Vector3d at = new Vector3d(1, 2, 3);
        assertSame(RunScale.NONE, EncounterSeams.runScaleAt(null, at), "the unfilled seam answers no step");
        EncounterSeams.fillRunScale((world, where) -> null);
        assertSame(RunScale.NONE, EncounterSeams.runScaleAt(null, at), "a fight in no run takes no step");
        EncounterSeams.fillRunScale((world, where) -> new RunScale(1.6, 1.3));
        assertEquals(new RunScale(1.6, 1.3), EncounterSeams.runScaleAt(null, at));
        EncounterSeams.fillRunScale((world, where) -> {
            throw new IllegalStateException("broken fill");
        });
        assertSame(RunScale.NONE, EncounterSeams.runScaleAt(null, at), "a failing fill costs only the step");
    }
}
