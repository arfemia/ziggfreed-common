package com.ziggfreed.common.effect.resist;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.ziggfreed.common.stats.damage.DamageResistances;

/**
 * The headroom and floor arithmetic, which has bitten before (the 1-point minimum was once missing, and a
 * player whose resistance rounded every small hit to nothing became untouchable): the summed resistance on
 * a key never passes the ceiling, and a hit that would land for a point without our share still does.
 * Mechanics only; no shipped number is pinned.
 */
class ResistArithmeticTest {

    private static final double EPS = 1e-9;

    // ==================== headroom ====================

    @Test
    void headroom_isTheCeilingLessWhatTheRestResists_roundedDown() {
        assertEquals(80, DamageResistReconciler.headroom(80, 0.0));
        assertEquals(54, DamageResistReconciler.headroom(80, 25.5), "rounded down, so the sum stays under");
        assertEquals(0, DamageResistReconciler.headroom(80, 80.0));
        assertEquals(0, DamageResistReconciler.headroom(80, 95.0), "armor alone past the ceiling leaves no room");
    }

    @Test
    void ceiling_neverReachesAWholeHit() {
        assertTrue(DamageResistReconciler.ceilingBucket(250.0) < DamageResistances.FULL_PERCENT,
                "no raised ceiling lets the sum reach immunity");
        assertEquals(DamageResistances.HIGHEST_PERCENT, DamageResistReconciler.ceilingBucket(100.0));
        assertEquals(0, DamageResistReconciler.ceilingBucket(-5.0));
        assertEquals(0, DamageResistReconciler.ceilingBucket(Double.NaN));
    }

    @Test
    void headroom_aVulnerabilityFreesNoRoomPastTheCeiling() {
        assertEquals(80, DamageResistReconciler.headroom(80, -30.0));
        assertEquals(80, DamageResistReconciler.headroom(80, Double.NaN));
        assertEquals(0, DamageResistReconciler.headroom(-5, 0.0));
    }

    @Test
    void bucket_isHeldSoTheSumNeverPassesTheCeiling() {
        int ceiling = 80;
        for (double others = 0.0; others <= 90.0; others += 7.3) {
            for (double raw = 0.0; raw <= 120.0; raw += 11.0) {
                int bucket = DamageResistReconciler.percentFor(raw, ceiling, others);
                assertTrue(bucket >= 0);
                assertTrue(bucket <= Math.round(raw) || bucket == 0, "never more than asked");
                if (others < ceiling) {
                    assertTrue(others + bucket <= ceiling + EPS, "sum " + (others + bucket) + " passes " + ceiling);
                } else {
                    assertEquals(0, bucket);
                }
            }
        }
    }

    // ==================== the 1-point minimum ====================

    @Test
    void floor_aHitWorthAPointWithoutOursStillLandsForOne() {
        // Half a point after armor, our share most of the sum: without it the hit lands for a point or more.
        ResistAfterArmor.Outcome out = ResistAfterArmor.outcome(0.5, 0.6, 0.5, 0.0, 0.0);
        assertTrue(out.withoutOurs() >= 1.0);
        assertEquals(1.0, out.landed(), EPS);
    }

    @Test
    void floor_neverRaisesAHitThatWasUnderAPointWithoutOurs() {
        ResistAfterArmor.Outcome out = ResistAfterArmor.outcome(0.3, 0.4, 0.2, 0.0, 0.0);
        assertTrue(out.landed() < 1.0);
        assertTrue(out.landed() <= out.withoutOurs());
    }

    @Test
    void floor_onlyWhileOursIsInTheSum() {
        ResistAfterArmor.Outcome vanilla = ResistAfterArmor.outcome(0.6, 0.5, 0.0, 0.0, 0.0);
        assertEquals(0.6, vanilla.landed(), EPS, "a sum with none of ours is the game's, untouched");
    }

    @Test
    void floor_aWholeHitSumIsLeftAlone() {
        assertEquals(0.0, ResistAfterArmor.outcome(0.0, 1.0, 0.3, 0.0, 0.0).landed(), EPS);
        assertEquals(2.0, ResistAfterArmor.outcome(2.0, 1.2, 0.3, 0.0, 0.5).landed(), EPS,
                "the sum is not under a whole hit: nothing to rescale");
    }

    @Test
    void floor_holdsWithARolledLandingToo() {
        ResistAfterArmor.Outcome out = ResistAfterArmor.outcome(1.2, 0.0, 0.0, 0.5, 0.0);
        assertEquals(1.0, out.landed(), EPS, "a rolled share is ours: a hurting fall still lands for a point");
        assertEquals(1.2, out.withoutOurs(), EPS);
    }

    // ==================== penetration and the rolled share ====================

    @Test
    void penetration_rescalesAsIfThatShareOfTheSumWereNotThere() {
        double amount = 50.0;
        double sum = 0.5;
        assertEquals(amount, ResistAfterArmor.outcome(amount, sum, 0.0, 0.0, 0.0).landed(), EPS);
        assertEquals(amount / (1.0 - sum), ResistAfterArmor.outcome(amount, sum, 0.0, 0.0, 1.0).landed(), EPS,
                "full penetration lands the hit as if nothing resisted it");
        double half = ResistAfterArmor.outcome(amount, sum, 0.0, 0.0, 0.5).landed();
        assertEquals(amount * (1.0 - sum * 0.5) / (1.0 - sum), half, EPS);
    }

    @Test
    void penetration_neverCutsAVulnerability() {
        assertEquals(30.0, ResistAfterArmor.outcome(30.0, -0.2, 0.0, 0.0, 1.0).landed(), EPS);
    }

    @Test
    void rollShare_isHeldUnderTheCeiling() {
        assertEquals(0.2, ResistAfterArmor.rollShare(20.0, 80.0, 0.3), EPS);
        assertEquals(0.1, ResistAfterArmor.rollShare(50.0, 80.0, 0.7), EPS, "only the room the sum leaves");
        assertEquals(0.0, ResistAfterArmor.rollShare(50.0, 80.0, 0.9), EPS);
        assertEquals(0.0, ResistAfterArmor.rollShare(-5.0, 80.0, 0.0), EPS);
    }

    @Test
    void blocked_isWhatOurShareTookOff() {
        ResistAfterArmor.Outcome out = ResistAfterArmor.outcome(40.0, 0.5, 0.25, 0.0, 0.0);
        // Without our quarter the sum is a quarter: the hit lands 1.5 times as heavy.
        assertEquals(60.0, out.withoutOurs(), EPS);
        assertEquals(20.0, out.blockedByOurs(), EPS);
    }
}
