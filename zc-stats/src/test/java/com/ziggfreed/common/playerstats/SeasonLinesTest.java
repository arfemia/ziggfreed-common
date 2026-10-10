package com.ziggfreed.common.playerstats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import javax.annotation.Nullable;

import org.junit.jupiter.api.Test;

import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;
import com.ziggfreed.common.playerstats.SeasonLines.Calendar;
import com.ziggfreed.common.playerstats.SeasonLines.State;
import com.ziggfreed.common.playerstats.asset.StatSourceAsset;

/**
 * What one moment adds to a player's record for a season page's lines: live, between seasons, absent. (Moved
 * from the Almanac's counter, which counted these lines in a record of its own before the stat store did.)
 */
class SeasonLinesTest {

    private static final Calendar LIVE_2026 = id -> "test_season".equals(id) ? State.liveIn(2026) : null;
    private static final Calendar BETWEEN = id -> "test_season".equals(id) ? State.BETWEEN : null;
    private static final Calendar ABSENT = id -> null;

    /** One year-round line and one live-only line, as the Almanac's shared test page writes them. */
    private static SeasonLines.Index index() throws IOException {
        return SeasonLines.Index.of(List.of(new SeasonLines.Page("Test_Season", "almanac.test.title", 10, List.of(
                line("Bombs_Thrown", "{ \"Kind\": \"USE_ITEM\", \"Target\": \"Test_Bomb_\", \"MatchMode\": \"PREFIX\","
                        + " \"Qualifier\": \"Throw\" }", false, "almanac.test.bombs"),
                line("Ghouls", "{ \"Kind\": \"KILL_ENTITY\", \"Target\": \"Test_Ghoul\", \"MatchMode\": \"EXACT\" }",
                        true, null)))));
    }

    private static SeasonLines.Line line(String lineId, String leaf, boolean liveOnly, @Nullable String textKey)
            throws IOException {
        StatSourceAsset asset = StatSourceAsset.CODEC.decodeJson(RawJsonReader.fromJsonString(leaf), new ExtraInfo());
        return new SeasonLines.Line("Test_Season", lineId, asset.toDefBuilder(lineId.toLowerCase(Locale.ROOT)).build(),
                liveOnly, textKey);
    }

    private static void count(PlayerStatsComponent record, Calendar calendar, String kindId, String target,
            @Nullable String qualifier, long amount) throws IOException {
        SeasonLines.apply(record, SeasonLines.writes(index(), calendar, kindId, target, qualifier, amount, null));
    }

    @Test
    void aMatchingMomentWhileTheSeasonIsOnCountsForTheSeasonAndForEverySeason() throws Exception {
        PlayerStatsComponent record = new PlayerStatsComponent();

        count(record, LIVE_2026, "USE_ITEM", "Test_Bomb_Rare", "Throw", 3L);

        assertEquals(3L, record.get("test_season/bombs_thrown"), "a moment adds its own amount");
        assertEquals(3L, record.view().season("test_season@2026/bombs_thrown"));
    }

    @Test
    void betweenSeasonsOnlyAYearRoundLineCountsAndOnlyForEverySeason() throws Exception {
        PlayerStatsComponent record = new PlayerStatsComponent();

        count(record, BETWEEN, "USE_ITEM", "Test_Bomb_Rare", "Throw", 1L);
        count(record, BETWEEN, "KILL_ENTITY", "Test_Ghoul", null, 1L);

        assertEquals(Set.of("test_season/bombs_thrown"), record.tallies().keySet(),
                "the live-only line waits for the season, and nothing is filed under a season between seasons");
        assertTrue(record.seasons().isEmpty(),
                "the live-only line waits for the season, and nothing is filed under a season between seasons");
    }

    @Test
    void aSeasonTheCalendarDoesNotAnswerForCountsNothing() throws Exception {
        PlayerStatsComponent record = new PlayerStatsComponent();

        count(record, ABSENT, "USE_ITEM", "Test_Bomb_Rare", "Throw", 1L);

        assertTrue(record.view().isEmpty(), "off means absent, even for a year-round line");
    }

    @Test
    void aMomentThatMatchesNoLineCountsNothing() throws Exception {
        PlayerStatsComponent record = new PlayerStatsComponent();

        count(record, LIVE_2026, "USE_ITEM", "Test_Bomb_Rare", "Crack", 1L);
        count(record, LIVE_2026, "KILL_ENTITY", "Other_Mob", null, 1L);
        count(record, LIVE_2026, "BREAK_BLOCK", "Test_Ghoul", null, 1L);

        assertTrue(record.view().isEmpty());
    }

    @Test
    void aMomentWithNoAmountCountsNothing() throws Exception {
        PlayerStatsComponent record = new PlayerStatsComponent();

        count(record, LIVE_2026, "KILL_ENTITY", "Test_Ghoul", null, 0L);
        count(record, LIVE_2026, "KILL_ENTITY", "Test_Ghoul", null, -4L);

        assertTrue(record.view().isEmpty(), "a tally never goes down");
    }

    @Test
    void eachSeasonKeepsItsOwnTallyAndEverySeasonSumsThem() throws Exception {
        PlayerStatsComponent record = new PlayerStatsComponent();

        count(record, id -> State.liveIn(2026), "KILL_ENTITY", "Test_Ghoul", null, 2L);
        count(record, id -> State.liveIn(2027), "KILL_ENTITY", "Test_Ghoul", null, 3L);

        assertEquals(2L, record.view().season("test_season@2026/ghouls"));
        assertEquals(3L, record.view().season("test_season@2027/ghouls"));
        assertEquals(5L, record.get("test_season/ghouls"));
    }
}
