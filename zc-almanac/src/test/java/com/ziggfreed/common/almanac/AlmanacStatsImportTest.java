package com.ziggfreed.common.almanac;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.ziggfreed.common.almanac.asset.AlmanacEntryAsset;
import com.ziggfreed.common.counter.CounterMap;
import com.ziggfreed.common.counter.PackedTallies;
import com.ziggfreed.common.playerstats.PlayerStatsComponent;
import com.ziggfreed.common.playerstats.SeasonLines;
import com.ziggfreed.common.playerstats.StatKind;

/**
 * The Almanac's stat lines in the player stat store: the one-time move copies them byte for byte and leaves the
 * Almanac's own records where they are, a second move changes nothing, and the store counts a page line under
 * the Almanac's own keys.
 */
class AlmanacStatsImportTest {

    private static final String BOMBS = AlmanacKeys.lifetime("test_season", "bombs_thrown");
    private static final String BOMBS_2026 = AlmanacKeys.season("test_season", 2026, "bombs_thrown");
    private static final String GHOULS = AlmanacKeys.lifetime("test_season", "ghouls");
    private static final String GHOULS_2025 = AlmanacKeys.season("test_season", 2025, "ghouls");

    /** A record before the move: both lines, every season and one, beside the Almanac's own records. */
    private static CounterMap almanac() {
        CounterMap tallies = new CounterMap();
        tallies.add(BOMBS, 7L);
        tallies.add(BOMBS_2026, 4L);
        tallies.add(GHOULS, 2L);
        tallies.add(GHOULS_2025, 2L);
        tallies.add(AlmanacKeys.lifetime("test_season", AlmanacKeys.ATTENDED), 2L);
        tallies.add(AlmanacKeys.season("test_season", 2026, AlmanacKeys.ATTENDED), 1L);
        tallies.add(AlmanacKeys.season("test_season", 2026, AlmanacKeys.RUNS), 2L);
        tallies.add(AlmanacKeys.owned("Test_Lantern"), 1L);
        return tallies;
    }

    private static AlmanacEntryAsset page() throws Exception {
        return AlmanacFixtures.page(AlmanacFixtures.SEASON_PAGE, "Test_Season");
    }

    @Test
    void theMoveCopiesEveryLineByteForByteAndLeavesTheRecordsWhereTheyAre() throws Exception {
        CounterMap almanac = almanac();
        Map<String, Long> before = almanac.all();
        PlayerStatsComponent record = new PlayerStatsComponent();

        AlmanacStatsImport.Split split = AlmanacStatsImport.split(almanac.all());
        assertTrue(record.importOnce(AlmanacStatsImport.CLAIM, split.lifetime(), split.seasons()));

        Map<String, Long> lifetime = new LinkedHashMap<>();
        lifetime.put(BOMBS, 7L);
        lifetime.put(GHOULS, 2L);
        Map<String, Long> seasons = new LinkedHashMap<>();
        seasons.put(BOMBS_2026, 4L);
        seasons.put(GHOULS_2025, 2L);
        assertEquals(PackedTallies.serialize(lifetime), PackedTallies.serialize(record.tallies()),
                "every every-season line, keys and values as the Almanac saved them, and no $ record");
        assertEquals(PackedTallies.serialize(seasons), PackedTallies.serialize(record.seasons()),
                "every season-year line, keys and values as the Almanac saved them, and no $ record");
        assertEquals(before, almanac.all(), "the Almanac record is never written");

        AlmanacIndex index = AlmanacIndex.of(Map.of("test_season", page()));
        assertEquals(before, AlmanacStatsImport.merged(almanac.all(), record.tallies(), record.seasons(),
                index.lines()).all(), "after the move the page reads exactly what it read before it");
    }

    @Test
    void aSecondMoveChangesNothingAndACountBeforeItAdds() {
        PlayerStatsComponent record = new PlayerStatsComponent();
        record.add(BOMBS, 3L);
        record.moveSeason(StatKind.SUM, BOMBS_2026, 3L);
        AlmanacStatsImport.Split split = AlmanacStatsImport.split(almanac().all());

        assertTrue(record.importOnce(AlmanacStatsImport.CLAIM, split.lifetime(), split.seasons()));
        Map<String, Long> tallies = record.tallies();
        Map<String, Long> seasons = record.seasons();
        assertFalse(record.importOnce(AlmanacStatsImport.CLAIM, split.lifetime(), split.seasons()), "the claim is held");

        assertEquals(tallies, record.tallies());
        assertEquals(seasons, record.seasons());
        assertEquals(10L, record.get(BOMBS), "a count made before the move adds to it");
        assertEquals(7L, record.view().season(BOMBS_2026), "a count made before the move adds to it");
        assertTrue(record.hasImported(AlmanacStatsImport.CLAIM));
    }

    @Test
    void theStoreCountsAPageLineUnderTheAlmanacsOwnKeys() throws Exception {
        AlmanacEntryAsset page = page();
        AlmanacIndex index = AlmanacIndex.of(Map.of("test_season", page));
        SeasonLines.Index lines = SeasonLines.Index.of(AlmanacStatLines.pages(Map.of("test_season", page), index));

        List<SeasonLines.Write> writes = SeasonLines.writes(lines, id -> SeasonLines.State.liveIn(2026), "USE_ITEM",
                "Test_Bomb_Rare", "Throw", 3L, null);

        assertEquals(List.of(new SeasonLines.Write(BOMBS, false, 3L), new SeasonLines.Write(BOMBS_2026, true, 3L)),
                writes, "a page line counts under the Almanac's keys, spelt as the Almanac spells them");
    }
}
