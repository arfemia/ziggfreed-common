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
import com.ziggfreed.common.reputation.event.ZigReputationRanksHeldEvent;

/** One REPUTATION_RANK moment per rank held, naming the rank and the reputation; the kind ships as a file. */
class ZigReputationProducerTest {

    @Test
    void oneMomentPerRankHeldNamingTheRankAndTheReputation() {
        UUID player = UUID.randomUUID();
        List<String> seen = new ArrayList<>();
        int fired = ZigReputationProducer.fanOut(
                new ZigReputationRanksHeldEvent(player, "Test_Old_Jack", List.of("Neutral", "Friendly")),
                (playerId, kind, target, qualifier, amount) ->
                        seen.add(playerId + "|" + kind + "|" + target + "|" + qualifier + "|" + amount));
        assertEquals(2, fired);
        assertEquals(List.of(player + "|REPUTATION_RANK|Neutral|Test_Old_Jack|1",
                player + "|REPUTATION_RANK|Friendly|Test_Old_Jack|1"), seen,
                "target the rank, qualifier the reputation, one per rank: a jump never misses a rank in between");
    }

    @Test
    void anEmptyCreditMovesNoStep() {
        assertEquals(0, ZigReputationProducer.fanOut(
                new ZigReputationRanksHeldEvent(UUID.randomUUID(), "Test_Old_Jack", List.of()),
                (playerId, kind, target, qualifier, amount) -> {
                    throw new AssertionError("nothing to credit");
                }));
    }

    @Test
    void theKindsFileNameIsTheProducersKind() throws Exception {
        String path = "/Server/ZiggfreedCommon/ObjectiveKinds/Reputation_Rank.json";
        String json;
        try (InputStream in = ZigReputationProducerTest.class.getResourceAsStream(path)) {
            assertNotNull(in, "zc-reputation ships " + path);
            json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        ObjectiveKindAsset kind = ObjectiveKindAsset.CODEC.decodeJsonAsset(RawJsonReader.fromJsonString(json),
                new AssetExtraInfo<>(new AssetExtraInfo.Data(ObjectiveKindAsset.class, "Reputation_Rank", null)));
        assertTrue(ZigReputationProducer.KIND.equalsIgnoreCase(kind.getId()), "the file name is the kind id");
    }
}
