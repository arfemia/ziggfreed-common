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
 * switched-on trainer placements, and a pick naming a placement that stands but is no trainer, or an id no placement
 * can carry, each a WARNING, never an ERROR; a trainer switched off, or one no placement file defines as yet, is no
 * fault (R608), one INFO line a trainer id however many picks name it.
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
    void eachAuthoringSlipIsOneWarningAndATrainerNotTeachingOneInfoLine() throws Exception {
        List<NpcPlacementAsset> placements = List.of(
                placement("Smith_A", "{ \"Trainer\": { \"Tracks\": [ \"Mining\", \"Minning\" ], "
                        + "\"Categories\": [ \"Gathering\", \"Gatherng\" ] } }"),
                placement("Smith_B", "{ \"Trainer\": {} }"),
                placement("Smith_Off", "{ \"Enabled\": false, \"Trainer\": {} }"),
                placement("Guide", "{ \"Identity\": { \"Role\": \"Guide\" } }"));
        List<TrackPickAsset> picks = List.of(
                pick("Lesson", "{ \"Where\": { \"Placements\": [ \"Smith_A\", \"Guide\", \"Gone\", \"Refused\" ] } }"),
                pick("Lesson_Two", "{ \"Where\": { \"Placements\": [ \"Gone\", \"Smith Off\", \"Smith_Off\", "
                        + "\"Smith_B\" ] } }"));
        Map<String, String> npc = Map.of("smith_a", "Smith", "smith_b", "smith", "smith_off", "Smith");

        List<Finding> findings = TrainerAudit.audit(placements, "Mining"::equals, "Gathering"::equals, npc::get,
                picks, Set.of("refused")::contains, id -> !"smith_b".equals(id));

        assertEquals(List.of(TrainerAudit.UNKNOWN_TRAINER_TRACK, TrainerAudit.UNKNOWN_TRAINER_CATEGORY,
                        TrainerAudit.SHARED_TRAINER_CHARACTER, TrainerAudit.SHARED_TRAINER_CHARACTER,
                        TrainerAudit.WHERE_NAMES_NO_TRAINER, TrainerAudit.WHERE_NAMES_NO_TRAINER,
                        TrainerAudit.TRAINER_SWITCHED_OFF, TrainerAudit.TRAINER_SWITCHED_OFF,
                        TrainerAudit.TRAINER_NOT_PLACED),
                findings.stream().map(Finding::code).toList(),
                "a misspelt track and category, the two switched-on placements of one character (the switched-off "
                        + "one is not counted), a Where naming a placement with no Trainer block and one naming an id "
                        + "no placement can carry, then a line for each trainer switched off (its file's Enabled, the "
                        + "owner's switch) and one for the trainer two picks name that no file defines (a refused one "
                        + "is named nowhere)");
        assertTrue(findings.subList(0, 6).stream().allMatch(f -> f.severity() == Severity.WARNING),
                "each slip is a WARNING");
        List<Finding> lines = findings.subList(6, findings.size());
        assertTrue(lines.stream().allMatch(f -> f.severity() == Severity.INFO),
                "a trainer switched off or not placed yet is no fault (R608)");
        assertTrue(lines.get(0).message().startsWith("1 pick(s) name the trainer 'smith_b', whose placement is "
                + "switched off"), "the owner's switch reads as switched off: " + lines.get(0).message());
        assertTrue(lines.get(1).message().startsWith("1 pick(s) name the trainer 'smith_off', whose placement is "
                + "switched off"), "a trainer file switched off reads as switched off: " + lines.get(1).message());
        assertTrue(lines.get(2).message().startsWith("2 pick(s) name the trainer 'gone', which no placement file"),
                "one line names the trainer and how many picks name it: " + lines.get(2).message());
    }
}
