package com.ziggfreed.common.encounter.run;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;

import org.junit.jupiter.api.Test;

import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;
import com.ziggfreed.common.encounter.asset.EncounterBindingAsset;

/**
 * The scale arithmetic, pure: the formula's shape, the floor of one, the authored ceiling, and the
 * absent-group defaults that multiply by nothing. The numbers here are the test's own fixtures.
 */
class EncounterScalingTest {

    private static EncounterBindingAsset.Scale scale(String json) throws IOException {
        return EncounterBindingAsset.Scale.CODEC.decodeJson(RawJsonReader.fromJsonString(json), new ExtraInfo());
    }

    @Test
    void anAbsentGroupMultipliesByNothing() {
        assertEquals(1.0, EncounterScaling.factor(null, 4, 50.0, 1.0), 1e-9);
        assertEquals(1.0, EncounterScaling.factor(null, 1, 0.0, 0.0), 1e-9, "a bad run multiplier reads as 1");
    }

    @Test
    void perMemberGrowthCountsMembersBeyondTheFirst() throws IOException {
        EncounterBindingAsset.Scale spec = scale("{\"HealthPerMember\": 0.5}");
        assertEquals(1.0, EncounterScaling.factor(spec, 1, 0.0, 1.0), 1e-9);
        assertEquals(1.0, EncounterScaling.factor(spec, 0, 0.0, 1.0), 1e-9, "nobody inside reads as one member");
        assertEquals(2.5, EncounterScaling.factor(spec, 4, 0.0, 1.0), 1e-9);
    }

    @Test
    void theFlatMultiplierTheRunMultiplierAndPowerCompose() throws IOException {
        EncounterBindingAsset.Scale spec = scale("{\"HealthPerMember\": 0.5, \"HealthMultiplier\": 2.0, "
                + "\"HealthPerPowerPoint\": 0.1, \"MaxHealthMultiplier\": 100}");
        // 2.0 x 1.5 x (1 + 0.5 x 1) + 0.1 x 10 = 4.5 + 1.0
        assertEquals(5.5, EncounterScaling.factor(spec, 2, 10.0, 1.5), 1e-9);
    }

    @Test
    void theResultIsHeldBetweenOneAndTheCeiling() throws IOException {
        EncounterBindingAsset.Scale spec = scale("{\"HealthPerMember\": 1.0, \"MaxHealthMultiplier\": 3.0}");
        assertEquals(3.0, EncounterScaling.factor(spec, 10, 0.0, 1.0), 1e-9);
        EncounterBindingAsset.Scale shrink = scale("{\"HealthMultiplier\": 0.25}");
        assertEquals(1.0, EncounterScaling.factor(shrink, 1, 0.0, 1.0), 1e-9, "never below the base");
        EncounterBindingAsset.Scale badCeiling = scale("{\"HealthPerMember\": 1.0, \"MaxHealthMultiplier\": 0.1}");
        assertEquals(1.0, EncounterScaling.factor(badCeiling, 5, 0.0, 1.0), 1e-9, "a ceiling under one reads as one");
    }
}
