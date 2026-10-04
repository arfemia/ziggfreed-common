package com.ziggfreed.common.encounter.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;
import com.ziggfreed.common.encounter.asset.EncounterBindingAsset;
import com.ziggfreed.common.encounter.seam.EncounterSeams;

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

    private static EncounterBindingAsset.AddScale adds(String json) throws IOException {
        return EncounterBindingAsset.AddScale.CODEC.decodeJson(RawJsonReader.fromJsonString(json), new ExtraInfo());
    }

    @Test
    void anAbsentAddsGroupScalesNoAdd() {
        assertEquals(1.0, EncounterScaling.addFactor(null, 4, 50.0), 1e-9);
    }

    @Test
    void addsGrowWithMembersAndPowerButNeverWithTheSpawnCallsMultiplier() throws IOException {
        EncounterBindingAsset.AddScale spec = adds("{\"HealthPerMember\": 0.25, \"HealthMultiplier\": 2.0, "
                + "\"HealthPerPowerPoint\": 0.1, \"MaxHealthMultiplier\": 100}");
        // 2.0 x (1 + 0.25 x 3) + 0.1 x 10 = 3.5 + 1.0; there is no run multiplier to pass
        assertEquals(4.5, EncounterScaling.addFactor(spec, 4, 10.0), 1e-9);
        assertEquals(2.0, EncounterScaling.addFactor(spec, 1, 0.0), 1e-9, "one member grows nothing per member");
    }

    @Test
    void addsAreHeldBetweenOneAndTheirOwnCeiling() throws IOException {
        assertEquals(3.0, EncounterScaling.addFactor(adds("{\"HealthPerMember\": 1.0, \"MaxHealthMultiplier\": 3.0}"),
                10, 0.0), 1e-9);
        assertEquals(1.0, EncounterScaling.addFactor(adds("{\"HealthMultiplier\": 0.5}"), 1, 0.0), 1e-9,
                "never below the health the role gives");
        assertEquals(1.0, EncounterScaling.addFactor(adds("{}"), 6, 0.0), 1e-9, "an empty group grows nothing");
    }

    @Test
    void theMemberCountIsTheRosterOrTheSeededPartyWhicheverIsLarger() {
        ZigEncounterRun seeded = ZigEncounterRun.forSpawn(new SpawnOptions(null, null, 1.0, false,
                List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID())));
        assertEquals(3, EncounterScaling.memberCount(List.of("a"), seeded));
        assertEquals(4, EncounterScaling.memberCount(List.of("a", "b", "c", "d"), seeded));
        assertEquals(0, EncounterScaling.memberCount(List.of(), new ZigEncounterRun()));
    }

    @Test
    void powerIsAskedForOnlyWhenTheScaleReadsIt() {
        AtomicInteger asked = new AtomicInteger();
        EncounterSeams.fillPowerSource((store, subject, members) -> {
            asked.incrementAndGet();
            return 12.0;
        });
        try {
            assertEquals(0.0, EncounterScaling.powerFor(null, null, List.of(), 0.0), 1e-9);
            assertEquals(0, asked.get(), "a scale that ignores power never asks the seam");
            assertEquals(12.0, EncounterScaling.powerFor(null, null, List.of(), 0.1), 1e-9);
            assertEquals(1, asked.get());
        } finally {
            EncounterSeams.resetForTests();
        }
    }

    @Test
    void anAddsScaleIsWrittenUnderAKeyOfItsOwn() {
        assertEquals("zc_encounter_add_scale", EncounterScaling.ADD_MODIFIER_KEY);
        assertNotEquals(EncounterScaling.MODIFIER_KEY, EncounterScaling.ADD_MODIFIER_KEY,
                "an add's modifier never collides with the subject's");
    }
}
