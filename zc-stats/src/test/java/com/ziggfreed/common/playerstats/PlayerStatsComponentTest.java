package com.ziggfreed.common.playerstats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;

/** The stat record's save format, and the one-time import that folds a legacy record in. */
class PlayerStatsComponentTest {

    @Test
    void theRecordRoundTripsThroughTheEngineCodecAndADamagedPairCostsOnlyItself() throws IOException {
        PlayerStatsComponent saved = PlayerStatsComponent.CODEC.decodeJson(RawJsonReader.fromJsonString(
                "{\"Tallies\":\"Mob_Kills:7|Mob_Kills/Skeleton:5|garbage|Deaths:x|Blocks_Broken:3|Other_Mod_Stat:2\","
                        + "\"Seasons\":\"hallows_eve@2026/Mob_Kills:4|broken:\","
                        + "\"Imports\":\"mymod:statistics@1,zc:test@1\"}"), new ExtraInfo());

        assertEquals(Map.of("Mob_Kills", 7L, "Mob_Kills/Skeleton", 5L, "Blocks_Broken", 3L, "Other_Mod_Stat", 2L),
                saved.tallies(), "a damaged pair is dropped, a key no stat registers rides along");
        assertEquals(Map.of("hallows_eve@2026/Mob_Kills", 4L), saved.seasons());
        assertEquals(Set.of("mymod:statistics@1", "zc:test@1"), saved.imports());

        ExtraInfo info = new ExtraInfo();
        PlayerStatsComponent restarted = PlayerStatsComponent.CODEC.decode(PlayerStatsComponent.CODEC.encode(saved, info), info);

        assertEquals(saved.tallies(), restarted.tallies());
        assertEquals(saved.seasons(), restarted.seasons());
        assertEquals(saved.imports(), restarted.imports());

        PlayerStatsComponent before = PlayerStatsComponent.CODEC.decodeJson(RawJsonReader.fromJsonString("{}"),
                new ExtraInfo());
        assertTrue(before.view().isEmpty(), "a player saved before the store existed holds nothing");
    }

    @Test
    void importingTheSameClaimTwiceEqualsImportingItOnce() {
        PlayerStatsComponent record = new PlayerStatsComponent();
        record.add("Mob_Kills", 2L);
        Map<String, Long> legacy = Map.of("Mob_Kills", 10L, "Mob_Kills/Skeleton", 4L, "Deaths", 1L);

        assertTrue(record.importOnce("mymod:statistics@1", legacy));
        Map<String, Long> once = record.tallies();
        assertFalse(record.importOnce("mymod:statistics@1", legacy), "the claim is held");

        assertEquals(once, record.tallies());
        assertEquals(Map.of("Mob_Kills", 12L, "Mob_Kills/Skeleton", 4L, "Deaths", 1L), record.tallies());
        assertTrue(record.hasImported("mymod:statistics@1"));
    }
}
