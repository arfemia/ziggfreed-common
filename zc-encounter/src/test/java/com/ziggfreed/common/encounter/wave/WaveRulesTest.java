package com.ziggfreed.common.encounter.wave;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * An endless run's schedule and ramp, which every endless reading and the ramp on each add read: which wave is a
 * boss wave and its return, which band slot raises each mob wave, when the size steps up, and how the ramp grows and
 * stops. Mechanics only, never a balance number a dungeon authors.
 */
class WaveRulesTest {

    @Test
    void aBossWaveComesEveryNthWaveAndCountsItsReturns() {
        WaveRules rules = new WaveRules(5, 4, List.of(1, 2), 1, 1, WaveRamp.NONE);

        for (int wave = 1; wave <= 20; wave++) {
            WaveRules.Wave at = rules.at(wave);
            assertEquals(wave % 5 == 0, at.boss(), "wave " + wave);
            assertEquals(at.boss() ? wave / 5 : 0, at.returns(), "wave " + wave);
            assertTrue(at.boss() == (at.slot() == 0), "a boss wave names no band slot, a mob wave one");
        }
    }

    @Test
    void bandsTakeTurnsInStintsSkippingTheBossWaves() {
        WaveRules rules = new WaveRules(5, 4, List.of(1, 2, 3), 1, 1, WaveRamp.NONE);

        assertEquals(List.of(1, 1, 1, 1), slots(rules, 1, 4), "the first stint is the first band");
        assertEquals(List.of(2, 2, 2, 2), slots(rules, 6, 9), "after the boss wave the next band");
        assertEquals(List.of(3, 3, 3, 3), slots(rules, 11, 14));
        assertEquals(List.of(1, 1, 1, 1), slots(rules, 16, 19), "then the turns start again");
    }

    @Test
    void withNoBossWavesEveryWaveIsAMobWave() {
        WaveRules rules = new WaveRules(null, 2, List.of(4, 2), 1, 1, WaveRamp.NONE);

        for (int wave = 1; wave <= 12; wave++) {
            assertFalse(rules.at(wave).boss());
        }
        assertEquals(List.of(4, 4, 2, 2, 4, 4), slots(rules, 1, 6), "the slots take turns in the order given");
    }

    @Test
    void theSizeStepsUpEachTimeTheBandsStartAgainUpToItsMax() {
        WaveRules rules = new WaveRules(5, 4, List.of(1, 2), 3, 1, WaveRamp.NONE);

        assertEquals(1, rules.at(1).size());
        assertEquals(1, rules.at(9).size(), "the first rotation is size 1");
        assertEquals(2, rules.at(11).size(), "the second rotation one bigger");
        assertEquals(3, rules.at(21).size());
        assertEquals(3, rules.at(500).size(), "never past the max");
        assertEquals(rules.at(9).size(), rules.at(10).size(), "a boss wave carries the size the waves before it reached");

        WaveRules slow = new WaveRules(5, 4, List.of(1, 2), 3, 2, WaveRamp.NONE);
        assertEquals(1, slow.at(11).size(), "Every 2 holds a size for two rotations");
        assertEquals(2, slow.at(21).size());
    }

    @Test
    void aSpacingUnderTwoIsNoBossAndAPoolWithNoSlotIsRefused() {
        assertFalse(new WaveRules(1, 4, List.of(1), 1, 1, WaveRamp.NONE).at(1).boss(), "every wave a boss is none");
        assertThrows(IllegalArgumentException.class, () -> new WaveRules(5, 4, List.of(0, 9), 1, 1, WaveRamp.NONE),
                "no slot from 1 to 4 means nothing could rise");
    }

    @Test
    void theRampStartsAtOneGrowsByWaveOrReturnAndStopsAtItsCap() {
        WaveRamp ramp = new WaveRamp(0.1, 0.05, 0.5, 0.25, 2.0);
        WaveRules rules = new WaveRules(5, 4, List.of(1), 1, 1, ramp);

        assertEquals(1.0, ramp.health(rules.at(1)), 1e-9, "the first wave is as its roles make it");
        assertEquals(1.0, ramp.damage(rules.at(1)), 1e-9);
        assertEquals(1.3, ramp.health(rules.at(4)), 1e-9, "a step per wave after the first");
        assertEquals(1.15, ramp.damage(rules.at(4)), 1e-9);
        assertEquals(1.0, ramp.health(rules.at(5)), 1e-9, "a boss's first return is as his role makes him");
        assertEquals(1.5, ramp.health(rules.at(10)), 1e-9, "each return after it a per-return step");
        assertEquals(1.25, ramp.damage(rules.at(10)), 1e-9);
        assertEquals(2.0, ramp.health(rules.at(1001)), 1e-9, "held to the cap");
        assertEquals(2.0, ramp.health(rules.at(100)), 1e-9, "a boss is held to it too");
    }

    @Test
    void aRampIsHeldToTheLibraryCeilingAndNoneGrowsNothing() {
        WaveRamp wild = new WaveRamp(1.0, 1.0, 1.0, 1.0, 1000.0);
        assertEquals(WaveRamp.CEILING, wild.cap(), 1e-9);
        assertFalse(WaveRamp.NONE.grows());
        assertTrue(wild.grows());
        assertEquals(1.0, WaveRamp.NONE.health(new WaveRules(null, 1, List.of(1), 1, 1, WaveRamp.NONE).at(50)), 1e-9);
    }

    @Test
    void theDamagePercentIsTheMultipliersRaiseInWholePercent() {
        assertEquals(0, WaveDamage.percentOf(1.0));
        assertEquals(25, WaveDamage.percentOf(1.25));
        assertEquals(0, WaveDamage.percentOf(0.5), "nothing below one");
    }

    private static List<Integer> slots(WaveRules rules, int from, int to) {
        List<Integer> out = new ArrayList<>();
        for (int wave = from; wave <= to; wave++) {
            out.add(rules.at(wave).slot());
        }
        return out;
    }
}
