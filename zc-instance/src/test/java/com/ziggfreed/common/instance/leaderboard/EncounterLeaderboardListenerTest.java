package com.ziggfreed.common.instance.leaderboard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.IOException;

import javax.annotation.Nonnull;

import org.junit.jupiter.api.Test;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;
import com.ziggfreed.common.encounter.asset.EncounterBindingAsset;

/** The bucket string a defeat's records are keyed by: the binding row's group, split by its knobs. */
class EncounterLeaderboardListenerTest {

    @Nonnull
    private static EncounterBindingAsset.Leaderboard group(@Nonnull String json) throws IOException {
        EncounterBindingAsset row = EncounterBindingAsset.CODEC.decodeAndInheritJsonAsset(
                RawJsonReader.fromJsonString("{ \"Leaderboard\": " + json + " }"), null,
                new AssetExtraInfo<>(new AssetExtraInfo.Data(EncounterBindingAsset.class, "Warden", null)));
        assertNotNull(row.getLeaderboard(), "the fixture decodes a group");
        return row.getLeaderboard();
    }

    // ==================== the bucket ====================

    @Test
    void theBucketIsTheRowsOwnUntilAKnobSplitsIt() throws IOException {
        assertEquals("encounters", EncounterLeaderboardListener.bucketFor(group("{ \"Bucket\": \"encounters\" }"), 4, "hard"));
        assertEquals("encounters:4",
                EncounterLeaderboardListener.bucketFor(group("{ \"Bucket\": \"encounters\", \"ByPartySize\": true }"), 4, "hard"));
        assertEquals("encounters:hard",
                EncounterLeaderboardListener.bucketFor(group("{ \"Bucket\": \"encounters\", \"ByDifficulty\": true }"), 4, "Hard"));
        assertEquals("encounters:4:hard", EncounterLeaderboardListener.bucketFor(
                group("{ \"Bucket\": \"encounters\", \"ByPartySize\": true, \"ByDifficulty\": true }"), 4, "hard"));
    }

    @Test
    void aRunWithNoDifficultyStillSplitsIntoItsOwnBucket() throws IOException {
        assertEquals("encounters:any",
                EncounterLeaderboardListener.bucketFor(group("{ \"Bucket\": \"encounters\", \"ByDifficulty\": true }"), 2, null));
    }
}
