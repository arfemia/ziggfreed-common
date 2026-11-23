package com.ziggfreed.common.effect.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.ziggfreed.common.effect.duration.EffectDurations;
import com.ziggfreed.common.stats.condition.ControlKinds;

/**
 * The one landing's rule for a foe held over and over (R828, R960), whatever kind and whoever calls it: a kind that
 * shrinks keeps 1 less the foe's Immunity share (full, three quarters, half, a quarter, then the foe turns Immune), a
 * free kind keeps its whole length and adds no Immunity, a step's own word overrides its kind's, and a fixed control
 * (R864, R880) skips the immunity, the shrink, the ramp, the carrier's resistance and the owner's added seconds, a
 * kind's cap still holding.
 */
class ControlLandingTest {

    private static final ControlKinds.Landing SHRINKS_AND_RAMPS = new ControlKinds.Landing(true, true, 25.0, 0.0);
    private static final ControlKinds.Landing FREE = new ControlKinds.Landing(false, false, 0.0, 0.0);

    @Test
    void aKindThatShrinks_isHeldShorterEachTime_untilTheFoeTurnsImmune() {
        ControlLanding.Steps steps = ControlLanding.steps(SHRINKS_AND_RAMPS, false, true, false, null, null);
        assertTrue(steps.asksImmunity());
        assertTrue(steps.shrinks());
        assertEquals(25.0, steps.rampAmount());
        float share = 0f;
        float[] expected = {1f, 0.75f, 0.5f, 0.25f};
        for (float kept : expected) {
            assertEquals(kept, ControlLanding.kept(share, steps.shrinks()), 1e-6f);
            share += (float) (steps.rampAmount() / 100.0);
        }
        assertEquals(0f, ControlLanding.kept(share, true), 1e-6f, "full Immunity: nothing left, the game's Immune");
        assertEquals(0f, ControlLanding.kept(1.4f, true), 1e-6f, "a share past 1 is held to 1");
    }

    @Test
    void oneCast_readsEachFoesShareOnce_andRampsEachFoeOnce() {
        ControlLanding.CastRecord cast = new ControlLanding.CastRecord();
        int[] reads = {0};
        float first = cast.share("foe", () -> {
            reads[0]++;
            return 0.25f;
        });
        float later = cast.share("foe", () -> {
            reads[0]++;
            return 0.5f;
        });
        assertEquals(0.25f, first, 1e-6f);
        assertEquals(0.25f, later, 1e-6f, "a later landing of the cast reuses the first read: no self-shrink");
        assertEquals(1, reads[0], "the foe's Immunity is read once per cast");
        assertEquals(0f, cast.share("other", () -> 0f), 1e-6f, "each foe has its own share");
        assertTrue(cast.step("foe"), "the cast's first ramping landing on a foe takes the step");
        assertFalse(cast.step("foe"), "every later one on that foe ramps nothing");
        assertTrue(cast.step("other"), "another foe takes its own step");
    }

    @Test
    void aFreeKind_keepsItsWholeLength_andAddsNoImmunity() {
        ControlLanding.Steps steps = ControlLanding.steps(FREE, false, true, false, null, null);
        assertFalse(steps.asksImmunity(), "no immunity stops a free kind");
        assertFalse(steps.shrinks());
        assertEquals(0.0, steps.rampAmount());
        assertEquals(1f, ControlLanding.kept(0.75f, steps.shrinks()), 1e-6f);
    }

    @Test
    void aStepsOwnWord_overridesItsKinds() {
        ControlLanding.Steps written = ControlLanding.steps(SHRINKS_AND_RAMPS, false, true, false, false, 10.0);
        assertFalse(written.shrinks(), "Diminish false: the full length");
        assertTrue(written.readsShare(), "the share is still read, for a cast's later landings on that foe");
        assertEquals(10.0, written.rampAmount(), "the step's own ramp");
        assertEquals(0.0, ControlLanding.steps(SHRINKS_AND_RAMPS, false, true, false, null, 0.0).rampAmount(),
                "a ramp of 0 adds none");
        assertEquals(0.0, ControlLanding.rampAmount(-3.0, SHRINKS_AND_RAMPS), "never below 0");
        assertTrue(ControlLanding.steps(FREE, false, true, false, true, null).shrinks(), "a step may shrink a free kind");
    }

    @Test
    void aFightsOwnControl_passesTheImmunity_andKeepsItsLength_butRampsAsItSays() {
        ControlLanding.Steps own = ControlLanding.steps(SHRINKS_AND_RAMPS, false, true, true, null, 0.0);
        assertFalse(own.asksImmunity());
        assertFalse(own.readsShare());
        assertFalse(own.shrinks());
        assertEquals(0.0, own.rampAmount());
    }

    @Test
    void aFixedControl_skipsTheImmunityTheShrinkAndTheRamp_whenTheSettingsSaySo() {
        ControlLanding.Steps fixed = ControlLanding.steps(SHRINKS_AND_RAMPS, true, true, false, true, 25.0);
        assertFalse(fixed.asksImmunity(), "never refused by immunity");
        assertFalse(fixed.readsShare());
        assertFalse(fixed.shrinks(), "never shorter for it, whatever the step writes");
        assertEquals(0.0, fixed.rampAmount(), "adds none, whatever the step writes");

        ControlLanding.Steps counted = ControlLanding.steps(SHRINKS_AND_RAMPS, true, false, false, null, null);
        assertTrue(counted.asksImmunity(), "with SkipsImmunity off a fixed control is one like any other");
        assertEquals(25.0, counted.rampAmount());
    }

    @Test
    void aFixedControl_skipsResistanceAndAddedTime_aKindsCapStillHolding() {
        double plain = EffectDurations.lasting(4.0, 2.0, 50.0, 80.0, 0.0, false, true, true);
        assertEquals(3.0, plain, 1e-9, "(4 + 2) less half");
        assertEquals(4.0, EffectDurations.lasting(4.0, 2.0, 50.0, 80.0, 0.0, true, true, true), 1e-9,
                "fixed: exactly its length");
        assertEquals(6.0, EffectDurations.lasting(4.0, 2.0, 50.0, 80.0, 0.0, true, false, true), 1e-9,
                "SkipsAddedSeconds off: 4 + 2, and still no cut");
        assertEquals(3.0, EffectDurations.lasting(4.0, 2.0, 50.0, 80.0, 3.0, true, true, true), 1e-9,
                "a kind's cap holds a fixed control too");
        assertEquals(1.2, EffectDurations.lasting(4.0, 0.0, 99.0, 70.0, 0.0), 1e-9, "the cut held to its cap");
        assertEquals(0.1, EffectDurations.lasting(0.0, 0.0, 0.0, 80.0, 0.0), 1e-9, "never below a tenth");
    }
}
