package com.ziggfreed.common.instance.leaderboard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import javax.annotation.Nonnull;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;
import com.ziggfreed.common.encounter.asset.EncounterBindingAsset;

/**
 * A boss fight's recorded keys read back under its binding row: a stale or foreign key left out, and the
 * layout named after the bucket being the one that names its tabs.
 */
class EncounterBoardsTest {

    @AfterEach
    void tearDown() {
        LeaderboardLayoutConfig.getInstance().loadDefaults(Map.of());
    }

    @Nonnull
    private static EncounterBindingAsset row(@Nonnull String id, @Nonnull String json) throws IOException {
        return EncounterBindingAsset.CODEC.decodeAndInheritJsonAsset(RawJsonReader.fromJsonString(json), null,
                new AssetExtraInfo<>(new AssetExtraInfo.Data(EncounterBindingAsset.class, id, null)));
    }

    @Nonnull
    private static EncounterBindingAsset.Leaderboard group(@Nonnull String json) throws IOException {
        EncounterBindingAsset.Leaderboard group = row("Boss", "{ \"Leaderboard\": " + json + " }").getLeaderboard();
        assertNotNull(group, "the fixture decodes a group");
        return group;
    }

    @Test
    void keysFromAnotherSplitAndAnotherBucketAreLeftOut() throws IOException {
        List<String> keys = List.of("bosses:2", "bosses:2:hard", "bosses", "bosses:", "bosses:x", "other:4", "bosses2:3");
        assertEquals(List.of(new EncounterBoards.Split(2, null)),
                EncounterBoards.splits(keys, group("{ \"Bucket\": \"bosses\", \"ByPartySize\": true }")));
        assertEquals(List.of(new EncounterBoards.Split(2, "hard")),
                EncounterBoards.splits(keys, group("{ \"Bucket\": \"bosses\", \"ByPartySize\": true, \"ByDifficulty\": true }")));
    }

    @Test
    void theLayoutIsTheOneNamedAfterTheBucket() throws IOException {
        LeaderboardLayout layout = new LeaderboardLayout("bosses", "unused", null, null, List.of(), List.of(), List.of());
        LeaderboardLayoutConfig.getInstance().loadDefaults(Map.of("Bosses", layout));
        assertSame(layout, EncounterBoards.layoutFor(group("{ \"Bucket\": \"bosses\" }")));
        assertNull(EncounterBoards.layoutFor(group("{ \"Bucket\": \"others\" }")));
    }
}
