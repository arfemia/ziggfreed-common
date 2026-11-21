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
import com.ziggfreed.common.instance.dungeon.DungeonMilestoneEvent;
import com.ziggfreed.common.progress.asset.ObjectiveKindAsset;

/**
 * One DUNGEON_MILESTONE per player a milestone names, Target the milestone id, Qualifier the dungeon id: the contract
 * an achievement authors against. The kind ships as a producible, counting file whose name is the producer's kind.
 */
class ZigDungeonMilestoneProducerTest {

    @Test
    void everyMemberAndTheStepperOnceTargetTheMilestoneQualifiedByTheDungeon() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID outside = UUID.randomUUID();
        List<String> seen = new ArrayList<>();
        ZigDungeonMilestoneProducer.Sink sink = (player, kind, target, qualifier) ->
                seen.add(player + "|" + kind + "|" + target + "|" + qualifier);

        int fired = ZigDungeonMilestoneProducer.fanOut(new DungeonMilestoneEvent("Sample_Dungeon_Daily", "",
                "run", DungeonMilestoneEvent.CLEAR_UNDER_PAR, null, List.of(a, b)), sink);
        assertEquals(2, fired);
        assertEquals(List.of(a + "|DUNGEON_MILESTONE|Clear_Under_Par|Sample_Dungeon_Daily",
                b + "|DUNGEON_MILESTONE|Clear_Under_Par|Sample_Dungeon_Daily"), seen,
                "a clear's milestone names no stepper: every winner, once each");

        seen.clear();
        assertEquals(1, ZigDungeonMilestoneProducer.fanOut(new DungeonMilestoneEvent("Crypt", "Hard", "run",
                "Puzzle_Lever", a, List.of(a)), sink), "a stepper among the members counts once");
        assertEquals(List.of(a + "|DUNGEON_MILESTONE|Puzzle_Lever|Crypt"), seen);

        seen.clear();
        assertEquals(2, ZigDungeonMilestoneProducer.fanOut(new DungeonMilestoneEvent("Crypt", "Hard", "run",
                "Puzzle_Lever", outside, List.of(a)), sink), "a stepper outside the members counts too");
        assertEquals(List.of(a + "|DUNGEON_MILESTONE|Puzzle_Lever|Crypt",
                outside + "|DUNGEON_MILESTONE|Puzzle_Lever|Crypt"), seen);
    }

    @Test
    void theKindsFileNameIsTheProducersKindAndItCounts() throws Exception {
        String path = "/Server/ZiggfreedCommon/ObjectiveKinds/Dungeon_Milestone.json";
        String json;
        try (InputStream in = ZigDungeonMilestoneProducerTest.class.getResourceAsStream(path)) {
            assertNotNull(in, "zc-instance ships " + path);
            json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        ObjectiveKindAsset kind = ObjectiveKindAsset.CODEC.decodeJsonAsset(RawJsonReader.fromJsonString(json),
                new AssetExtraInfo<>(new AssetExtraInfo.Data(ObjectiveKindAsset.class, "Dungeon_Milestone", null)));
        assertTrue(ZigDungeonMilestoneProducer.KIND.equalsIgnoreCase(kind.getId()), "the file name is the kind id");
        assertEquals(Boolean.TRUE, kind.getProducible(), "a step of it can progress");
        assertEquals(Boolean.FALSE, kind.getValueBased(), "each milestone adds one, never a high-water mark");
    }
}
