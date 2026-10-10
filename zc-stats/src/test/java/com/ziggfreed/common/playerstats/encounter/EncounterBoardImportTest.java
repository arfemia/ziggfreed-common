package com.ziggfreed.common.playerstats.encounter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;
import com.ziggfreed.common.instance.leaderboard.Leaderboard;
import com.ziggfreed.common.instance.leaderboard.LeaderboardEntry;
import com.ziggfreed.common.instance.leaderboard.StatColumnDef;
import com.ziggfreed.common.instance.leaderboard.statboard.StatBoard;
import com.ziggfreed.common.instance.leaderboard.statboard.StatBoardSource;
import com.ziggfreed.common.instance.leaderboard.statboard.StatPeriod;
import com.ziggfreed.common.instance.leaderboard.statboard.StatSectionAsset;
import com.ziggfreed.common.instance.leaderboard.statboard.StatSectionConfig;
import com.ziggfreed.common.playerstats.PlayerStatsComponent;

/**
 * The old encounter board's rows, in the shape its file holds them, imported into the stat board's
 * {@code all_time} and into a player's record: each row maps to its fight's four stat lines, and a second
 * import changes nothing.
 */
class EncounterBoardImportTest {

    private static final UUID ADA = new UUID(0, 1);
    private static final UUID BEN = new UUID(0, 2);

    /** A stat board that is on and describes no column; the import needs only its switch. */
    private static final StatBoardSource ON = new StatBoardSource() {
        @Override
        public boolean enabled() {
            return true;
        }

        @Override
        @Nullable
        public StatColumnDef column(@Nonnull String line) {
            return null;
        }
    };

    @AfterEach
    void reset() {
        StatBoard.use(null);
        StatBoard.setSource(null);
        StatSectionConfig.getInstance().mergePackLayer(Map.of());
    }

    @Nonnull
    private static StatSectionAsset bossFights() throws IOException {
        return StatSectionAsset.CODEC.decodeAndInheritJsonAsset(RawJsonReader.fromJsonString(
                "{ \"Stats\": [\"Encounters_Won/*\", \"Encounter_Best_Time/*\", \"Encounter_Best_Share/*\","
                        + " \"Encounter_Damage_Dealt/*\"] }"), null,
                new AssetExtraInfo<>(new AssetExtraInfo.Data(StatSectionAsset.class, "Boss_Fights", null)));
    }

    /** {@code uuid -> stat key -> value} over the stat board's {@code all_time}. */
    @Nonnull
    private static Map<UUID, Map<String, Long>> allTime(@Nonnull Leaderboard board) {
        Map<UUID, Map<String, Long>> out = new HashMap<>();
        for (Map.Entry<UUID, LeaderboardEntry> e : board.forBucket(StatPeriod.ALL_TIME).entrySet()) {
            out.put(e.getKey(), e.getValue().counters().all());
        }
        return out;
    }

    @Test
    void theOldRowsMapToTheirFightsLinesOnceOnTheBoardAndOnceInTheRecord() throws IOException {
        // The rows the encounter board's listener wrote: two wins of one fight, one of a split fight.
        Leaderboard old = new Leaderboard("encounter-leaderboard", Runnable::run);
        old.record("zc_encounter_example", ADA, "Ada", 100, 95, true, Map.of("damage_dealt", 900L, "damage_taken", 40L));
        old.record("zc_encounter_example", ADA, "Ada", 60, 120, true, Map.of("damage_dealt", 500L));
        old.record("warden:4:hard", ADA, "Ada", 35, 200, true, Map.of("damage_dealt", 300L));
        old.record("zc_encounter_example", BEN, "Ben", 40, 95, true, Map.of("damage_dealt", 450L));
        Leaderboard board = new Leaderboard("stats-board", Runnable::run);
        StatBoard.use(board);
        StatBoard.setSource(ON);
        StatSectionConfig.getInstance().mergePackLayer(Map.of("boss_fights", bossFights()));

        assertTrue(EncounterBoardImport.intoBoard(old));
        Map<UUID, Map<String, Long>> once = allTime(board);
        Map<String, Long> ada = once.get(ADA);
        assertNotNull(ada);
        assertEquals(Map.of(
                "Encounters_Won/zc_encounter_example", 2L,
                "Encounter_Best_Time/zc_encounter_example", 95L,
                "Encounter_Best_Share/zc_encounter_example", 100L,
                "Encounter_Damage_Dealt/zc_encounter_example", 1400L,
                "Encounters_Won/warden:4:hard", 1L,
                "Encounter_Best_Time/warden:4:hard", 200L,
                "Encounter_Best_Share/warden:4:hard", 35L,
                "Encounter_Damage_Dealt/warden:4:hard", 300L), ada,
                "plays, best time, best score and damage dealt, each on its fight's line; no total is ranked");
        assertEquals("Ben", board.forBucket(StatPeriod.ALL_TIME).get(BEN).name);

        assertFalse(EncounterBoardImport.intoBoard(old), "the board holds the claim");
        assertEquals(once, allTime(board), "a second import changes nothing");

        PlayerStatsComponent record = new PlayerStatsComponent();
        Map<String, Long> rows = EncounterBoardImport.rowsOf(old).get(ADA).stats();
        assertTrue(record.importOnce(EncounterBoardImport.CLAIM, rows));
        Map<String, Long> folded = record.tallies();
        assertFalse(record.importOnce(EncounterBoardImport.CLAIM, rows), "the record holds the claim");
        assertEquals(folded, record.tallies());
        assertEquals(3L, record.get("Encounters_Won"), "the record's total counts every old win");
        assertEquals(95L, record.get("Encounter_Best_Time"), "and keeps the fastest of them");
        assertEquals(ada.get("Encounter_Damage_Dealt/zc_encounter_example"),
                record.get("Encounter_Damage_Dealt/zc_encounter_example"), "the record and the board agree");
    }
}
