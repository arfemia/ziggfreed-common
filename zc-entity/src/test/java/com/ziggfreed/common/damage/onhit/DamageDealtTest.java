package com.ziggfreed.common.damage.onhit;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * The true share of every hit ({@code DamageDealt}): -10 points multiply the whole hit by nine tenths, whatever else
 * built it, held under the stat file's ceiling (0 as shipped, so it never raises a hit) and never below nothing.
 */
class DamageDealtTest {

    @Test
    void pointsMultiplyTheWholeHitUnderTheCeiling() {
        assertEquals(0.9, DamageDealt.factor(-10.0, 0.0), 1e-12, "a tenth less of every hit");
        assertEquals(1.0, DamageDealt.factor(0.0, 0.0), "no points, the hit as it is");
        assertEquals(1.0, DamageDealt.factor(25.0, 0.0), "above the shipped ceiling of 0, nothing more");
        assertEquals(1.1, DamageDealt.factor(10.0, 50.0), 1e-12, "a server that raises the ceiling may raise a hit");
        assertEquals(0.0, DamageDealt.factor(-150.0, 0.0), "never below nothing");
        assertEquals(1.0, DamageDealt.factor(Double.NaN, 0.0), "a number that is not one changes nothing");
    }
}
