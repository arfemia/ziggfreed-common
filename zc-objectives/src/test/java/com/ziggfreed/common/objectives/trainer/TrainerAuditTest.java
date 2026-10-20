package com.ziggfreed.common.objectives.trainer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;
import com.ziggfreed.common.npc.placement.asset.NpcPlacementAsset;
import com.ziggfreed.common.track.pick.TrackPickAsset;
import com.ziggfreed.common.validation.Finding;
import com.ziggfreed.common.validation.Severity;

/**
 * The trainers' audit over loaded files: an unknown track or category in a trainer block, one character at two
 * switched-on trainer placements, and a pick naming a placement that is no trainer, each a WARNING, never an ERROR.
 */
class TrainerAuditTest {

    private static NpcPlacementAsset placement(String id, String json) throws IOException {
        AssetExtraInfo.Data data = new AssetExtraInfo.Data(NpcPlacementAsset.class, id, null);
        return NpcPlacementAsset.CODEC.decodeAndInheritJsonAsset(RawJsonReader.fromJsonString(json), null,
                new AssetExtraInfo<>(data));
    }

    private static TrackPickAsset pick(String id, String json) throws IOException {
        AssetExtraInfo.Data data = new AssetExtraInfo.Data(TrackPickAsset.class, id, null);
        return TrackPickAsset.CODEC.decodeJsonAsset(RawJsonReader.fromJsonString(json), new AssetExtraInfo<>(data));
    }

    @Test
    void eachAuthoringSlipIsOneWarning() throws Exception {
        List<NpcPlacementAsset> placements = List.of(
                placement("Smith_A", "{ \"Trainer\": { \"Tracks\": [ \"Mining\", \"Minning\" ], "
                        + "\"Categories\": [ \"Gathering\", \"Gatherng\" ] } }"),
                placement("Smith_B", "{ \"Trainer\": {} }"),
                placement("Smith_Off", "{ \"Enabled\": false, \"Trainer\": {} }"),
                placement("Guide", "{ \"Identity\": { \"Role\": \"Guide\" } }"));
        List<TrackPickAsset> picks = List.of(
                pick("Lesson", "{ \"Where\": { \"Placements\": [ \"Smith_A\", \"Guide\", \"Gone\", \"Refused\" ] } }"));
        Map<String, String> npc = Map.of("smith_a", "Smith", "smith_b", "smith", "smith_off", "Smith");

        List<Finding> findings = TrainerAudit.audit(placements, "Mining"::equals, "Gathering"::equals, npc::get,
                picks, Set.of("refused")::contains);

        assertEquals(List.of(TrainerAudit.UNKNOWN_TRAINER_TRACK, TrainerAudit.UNKNOWN_TRAINER_CATEGORY,
                        TrainerAudit.SHARED_TRAINER_CHARACTER, TrainerAudit.SHARED_TRAINER_CHARACTER,
                        TrainerAudit.WHERE_NAMES_NO_TRAINER, TrainerAudit.WHERE_NAMES_NO_TRAINER),
                findings.stream().map(Finding::code).toList(),
                "a misspelt track and category, the two switched-on placements of one character (the switched-off "
                        + "one is not counted), and the two Where ids that are no trainer (a refused one is named nowhere)");
        assertTrue(findings.stream().allMatch(f -> f.severity() == Severity.WARNING));
    }
}
