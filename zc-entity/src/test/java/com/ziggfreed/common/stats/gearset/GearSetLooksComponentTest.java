package com.ziggfreed.common.stats.gearset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;

/**
 * The saved look record ({@link GearSetLooksComponent}): its codec round trip, exercised through the
 * {@code BuilderCodec} directly (never a live store, engine state a unit JVM cannot stand up), and
 * the record rule, a whole replacement each recompute, blank ids dropped, order kept.
 */
class GearSetLooksComponentTest {

    @Test
    void theRecordRoundTripsThroughTheCodecInOrder() {
        GearSetLooksComponent original = new GearSetLooksComponent();
        original.record(List.of("Night_Set_Look", "Retired_Set_Look"));

        ExtraInfo info = new ExtraInfo();
        var bson = GearSetLooksComponent.CODEC.encode(original, info);
        GearSetLooksComponent decoded = GearSetLooksComponent.CODEC.decode(bson, info);

        assertEquals(List.of("Night_Set_Look", "Retired_Set_Look"), List.copyOf(decoded.effects()));
    }

    @Test
    void theSavedKeyIsEffects() throws IOException {
        GearSetLooksComponent decoded = GearSetLooksComponent.CODEC.decodeJson(
                RawJsonReader.fromJsonString("{\"Effects\":[\"Night_Set_Look\",\"Night_Set_Look\",\"\"]}"),
                new ExtraInfo());

        assertEquals(List.of("Night_Set_Look"), List.copyOf(decoded.effects()),
                "a repeated id reads once and a blank one not at all");
    }

    @Test
    void aPlayerSavedWithoutARecordReadsAnEmptyOne() throws IOException {
        GearSetLooksComponent decoded = GearSetLooksComponent.CODEC.decodeJson(
                RawJsonReader.fromJsonString("{}"), new ExtraInfo());

        assertTrue(decoded.effects().isEmpty());
    }

    @Test
    void eachRecordReplacesTheLastAndACloneIsItsOwn() {
        GearSetLooksComponent looks = new GearSetLooksComponent();
        looks.record(List.of("Night_Set_Look"));
        GearSetLooksComponent copy = looks.clone();

        looks.record(Arrays.asList("Amber_Set_Look", null, " "));

        assertEquals(List.of("Amber_Set_Look"), List.copyOf(looks.effects()),
                "what the engine asked for this time, never a union with last time");
        assertEquals(List.of("Night_Set_Look"), List.copyOf(copy.effects()), "a clone keeps its own record");

        looks.record(List.of());
        assertTrue(looks.effects().isEmpty(), "a recompute that asks for nothing (a corpse) records nothing");
    }
}
