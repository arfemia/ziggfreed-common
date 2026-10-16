package com.ziggfreed.common.objectives.producer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;
import com.ziggfreed.common.progress.asset.ObjectiveKindAsset;
import com.ziggfreed.common.stats.gearset.ZigGearSetTierChangedEvent;

/**
 * One GEAR_SET_TIER moment per tier that came on, naming the set and carrying the tier's size; none
 * for a tier going off. The kind ships as a value-based file, so a step keeps the largest tier seen.
 * The event is built with no live player reference: a unit JVM has none, and the fan-out never reads it.
 */
class ZigGearSetProducerTest {

    @Test
    void aTierComingOnIsOneMomentNamingTheSetWithTheTiersSize() {
        UUID player = UUID.randomUUID();
        List<String> seen = new ArrayList<>();
        int fired = ZigGearSetProducer.fanOut(event(player, 4, true),
                (playerId, kind, target, qualifier, amount) ->
                        seen.add(playerId + "|" + kind + "|" + target + "|" + qualifier + "|" + amount));
        assertEquals(1, fired);
        assertEquals(List.of(player + "|GEAR_SET_TIER|night_set|null|4"), seen,
                "target the set, no qualifier, the amount the tier's size, never the pieces worn");
    }

    @Test
    void aTierGoingOffOrOneWithNoSizeMovesNoStep() {
        ZigGearSetProducer.Sink refuse = (playerId, kind, target, qualifier, amount) -> {
            throw new AssertionError("nothing to credit");
        };
        assertEquals(0, ZigGearSetProducer.fanOut(event(UUID.randomUUID(), 4, false), refuse));
        assertEquals(0, ZigGearSetProducer.fanOut(event(UUID.randomUUID(), 0, true), refuse));
    }

    @Test
    void theKindsFileNameIsTheProducersKindAndItKeepsTheLargestTier() throws Exception {
        String path = "/Server/ZiggfreedCommon/ObjectiveKinds/Gear_Set_Tier.json";
        String json;
        try (InputStream in = ZigGearSetProducerTest.class.getResourceAsStream(path)) {
            assertNotNull(in, "zc-entity ships " + path);
            json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        ObjectiveKindAsset kind = ObjectiveKindAsset.CODEC.decodeJsonAsset(RawJsonReader.fromJsonString(json),
                new AssetExtraInfo<>(new AssetExtraInfo.Data(ObjectiveKindAsset.class, "Gear_Set_Tier", null)));
        assertTrue(ZigGearSetProducer.KIND.equalsIgnoreCase(kind.getId()), "the file name is the kind id");
        assertEquals(Boolean.TRUE, kind.getValueBased(), "a step keeps the largest tier seen, never a sum");
    }

    private static ZigGearSetTierChangedEvent event(UUID player, int tierSize, boolean active) {
        return new ZigGearSetTierChangedEvent(player, null, "night_set", 1, tierSize, active, tierSize, 6, 5,
                null, List.of(), List.of());
    }
}
