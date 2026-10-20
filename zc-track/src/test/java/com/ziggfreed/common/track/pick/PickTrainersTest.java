package com.ziggfreed.common.track.pick;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;

/**
 * The seam that says where a trainer pick is taken (R608): only at the trainers its {@code Where} names that teach,
 * and on its track's own page once none of them does. It ships answering every named placement (what a track alone
 * can say), and an answer that throws keeps the pick at its trainers rather than moving it.
 */
class PickTrainersTest {

    @AfterEach
    void restoreTheDefault() {
        PickTrainers.install(null);
    }

    private static TrackPickAsset pick(String json) throws IOException {
        AssetExtraInfo.Data data = new AssetExtraInfo.Data(TrackPickAsset.class, "Test_Pick", null);
        return TrackPickAsset.CODEC.decodeJsonAsset(RawJsonReader.fromJsonString(json), new AssetExtraInfo<>(data));
    }

    @Test
    void unfilledEveryNamedPlacementTeaches() throws Exception {
        TrackPickAsset taught = pick("{ \"Where\": { \"Placements\": [ \"Trainer_A\", \"Trainer_B\" ] } }");
        assertEquals(List.of("trainer_a", "trainer_b"), PickTrainers.of(taught));
        assertEquals(List.of(), PickTrainers.of(pick("{}")), "a pick naming no trainer is the track's own");
        assertFalse(PickTrainers.teaches(" "), "a blank id never teaches");
    }

    @Test
    void aPickWhoseTrainersAllStandDownIsTakenOnItsTrack() throws Exception {
        TrackPickAsset taught = pick("{ \"Where\": { \"Placements\": [ \"Trainer_A\", \"Trainer_B\" ] } }");
        PickTrainers.install("trainer_b"::equals);
        assertEquals(List.of("trainer_b"), PickTrainers.of(taught), "only the trainers that teach keep it");

        PickTrainers.install(id -> false);
        assertTrue(PickTrainers.of(taught).isEmpty(), "with no trainer teaching, it is learned on the track's page");
    }

    @Test
    void anAnswerThatThrowsKeepsThePickAtItsTrainers() throws Exception {
        TrackPickAsset taught = pick("{ \"Where\": { \"Placements\": [ \"Trainer_A\" ] } }");
        PickTrainers.install(id -> {
            throw new IllegalStateException("no store");
        });
        assertEquals(List.of("trainer_a"), PickTrainers.of(taught));
    }
}
