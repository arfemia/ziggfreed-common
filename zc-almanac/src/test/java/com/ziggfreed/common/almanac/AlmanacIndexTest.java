package com.ziggfreed.common.almanac;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.ziggfreed.common.almanac.asset.AlmanacEntryAsset;
import com.ziggfreed.common.almanac.asset.AlmanacEntryConfig;

/** The stat lines the counter checks a moment against, filed by kind. */
class AlmanacIndexTest {

    @Test
    void linesAreFiledByKindWithoutRegardToCase() throws Exception {
        AlmanacIndex index = AlmanacIndex.of(Map.of("test_season",
                AlmanacFixtures.page(AlmanacFixtures.SEASON_PAGE, "Test_Season")));

        List<AlmanacIndex.Line> bombs = index.forKind("use_item");
        assertEquals(1, bombs.size());
        assertEquals("test_season", bombs.get(0).eventId());
        assertEquals("bombs_thrown", bombs.get(0).statId());
        assertFalse(bombs.get(0).liveOnly());
        assertTrue(bombs.get(0).def().matches("Test_Bomb_Rare", "throw"),
                "a stat line compares the way a quest step does");
        assertEquals(1, index.forKind("KILL_ENTITY").size());
        assertTrue(index.forKind("BREAK_BLOCK").isEmpty());
        assertTrue(index.forKind(null).isEmpty());
    }

    @Test
    void aLineWithNoKindOrAReservedNameIsSkippedAndTheRestStay() throws Exception {
        AlmanacEntryAsset page = AlmanacFixtures.page("""
                { "Stats": { "No_Kind": { "Target": "X" },
                             "Bad/Name": { "Kind": "KILL_ENTITY" },
                             "Good": { "Kind": "KILL_ENTITY" } } }
                """, "Test_Season");

        AlmanacIndex index = AlmanacIndex.of(Map.of("test_season", page));

        assertEquals(List.of("good"), index.forKind("KILL_ENTITY").stream().map(AlmanacIndex.Line::statId).toList());
    }

    @Test
    void theIndexTracksEveryItemACollectionListsAcrossPages() throws Exception {
        AlmanacIndex index = AlmanacIndex.of(Map.of(
                "season_a", AlmanacFixtures.page("{ \"Sections\": [ { \"Collection\": { \"Items\": [ { \"Item\": \"Test_Lantern\" } ] } } ] }", "Season_A"),
                "season_b", AlmanacFixtures.page("{ \"Sections\": [ { \"Collection\": { \"Items\": [ { \"Item\": \"Test_Bomb\", \"Hidden\": true } ] } } ] }", "Season_B")));
        assertTrue(index.tracks("test_lantern"));
        assertTrue(index.tracks("TEST_BOMB"), "hidden or not, every listed item is tracked");
        assertFalse(index.tracks("Rock_Stone"));
        assertEquals(Set.of("test_lantern", "test_bomb"), index.trackedItems());
        assertTrue(AlmanacIndex.EMPTY.trackedItems().isEmpty());
    }

    @Test
    void aPageWhoseNameTheFormatReservesIsSkippedWhole() throws Exception {
        AlmanacIndex index = AlmanacIndex.of(Map.of("bad@season",
                AlmanacFixtures.page(AlmanacFixtures.SEASON_PAGE, "Bad_Season")));

        assertTrue(index.isEmpty());
    }

    @Test
    void theConfigRebuildsTheIndexWithEveryLayer() throws Exception {
        try {
            AlmanacEntryConfig.getInstance().mergePackLayer(Map.of("test_season",
                    AlmanacFixtures.page(AlmanacFixtures.SEASON_PAGE, "Test_Season")));
            assertEquals(1, AlmanacEntryConfig.getInstance().index().forKind("USE_ITEM").size());
        } finally {
            AlmanacEntryConfig.getInstance().mergePackLayer(Map.of());
        }
        assertTrue(AlmanacEntryConfig.getInstance().index().isEmpty(), "an emptied layer empties the index");
    }
}
