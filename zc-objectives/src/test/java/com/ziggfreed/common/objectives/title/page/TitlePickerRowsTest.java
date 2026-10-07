package com.ziggfreed.common.objectives.title.page;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;
import com.ziggfreed.common.objectives.title.TitleAsset;
import com.ziggfreed.common.objectives.title.TitleConfig;
import com.ziggfreed.common.objectives.title.page.TitlePickerRows.Line;
import com.ziggfreed.common.objectives.title.page.TitlePickerRows.Plan;
import com.ziggfreed.common.objectives.title.page.TitlePickerRows.Tile;
import com.ziggfreed.common.ui.kit.Progress;

/**
 * What the picker shows, worked out with no builder in hand: the earned titles on offer as tiles in picker order with
 * at most one shown; every other title on offer as a line naming the achievement that gives it and the player's
 * progress; "No titles yet" over those lines when nothing is earned, and "no titles to earn" when the server offers
 * none. A switched-off or unknown title is never listed.
 */
class TitlePickerRowsTest {

    private static final TitleSources.Source LANTERNS = new TitleSources.Source("lantern_keeper", new Progress(1, 3));

    private static final Function<String, TitleSources.Source> SOURCES =
            id -> Map.of("keeper_of_lanterns", LANTERNS).get(id);

    private static TitleAsset title(String json, String id) throws IOException {
        AssetExtraInfo.Data data = new AssetExtraInfo.Data(TitleAsset.class, id, null);
        return TitleAsset.CODEC.decodeAndInheritJsonAsset(
                RawJsonReader.fromJsonString(json), null, new AssetExtraInfo<>(data));
    }

    @BeforeEach
    void titles() throws Exception {
        TitleConfig.getInstance().mergePackLayer(Map.of(
                "Hallows_Eve_Hallowed", title("{ \"Order\": 10 }", "Hallows_Eve_Hallowed"),
                "Pumpkin_King", title("{ \"Order\": 5 }", "Pumpkin_King"),
                "Keeper_Of_Lanterns", title("{ \"Order\": 20 }", "Keeper_Of_Lanterns"),
                "Off", title("{ \"Enabled\": false }", "Off")));
    }

    @AfterEach
    void clear() {
        TitleConfig.getInstance().mergePackLayer(Map.of());
    }

    @Test
    void earnedTilesAreTheOfferedUnlockedTitlesInPickerOrderWithTheShownOne() {
        Plan plan = TitlePickerRows.plan(List.of("hallows_eve_hallowed", "pumpkin_king", "off", "never_defined"),
                "Hallows_Eve_Hallowed", TitleConfig.getInstance(), SOURCES);

        assertEquals(List.of(new Tile("pumpkin_king", false), new Tile("hallows_eve_hallowed", true)), plan.earned());
    }

    @Test
    void everyOtherOfferedTitleIsALineWithTheAchievementThatGivesItAndTheProgress() {
        Plan plan = TitlePickerRows.plan(List.of("pumpkin_king"), "pumpkin_king", TitleConfig.getInstance(),
                SOURCES);

        assertEquals(List.of(new Line("hallows_eve_hallowed", null), new Line("keeper_of_lanterns", LANTERNS)),
                plan.notEarned(), "picker order; a title no visible achievement gives still lists, with no source");
        assertFalse(plan.noneYet());
        assertFalse(plan.noneOnServer());
    }

    @Test
    void nothingEarnedReadsNoneYetOverTheTitlesStillToEarn() {
        Plan plan = TitlePickerRows.plan(List.of(), null, TitleConfig.getInstance(), SOURCES);

        assertEquals(List.of(), plan.earned());
        assertEquals(List.of(new Line("pumpkin_king", null), new Line("hallows_eve_hallowed", null),
                new Line("keeper_of_lanterns", LANTERNS)), plan.notEarned());
        assertTrue(plan.noneYet(), "the empty state is the list of what gives one");
        assertFalse(plan.noneOnServer());
    }

    @Test
    void aServerOfferingNoTitleReadsNoneOnServer() {
        TitleConfig.getInstance().mergePackLayer(Map.of());

        Plan plan = TitlePickerRows.plan(List.of("off", "never_defined"), "off", TitleConfig.getInstance(), SOURCES);

        assertTrue(plan.noneOnServer());
        assertFalse(plan.noneYet(), "one empty state, not both");
        assertEquals(List.of(), plan.earned());
        assertEquals(List.of(), plan.notEarned());
    }

    @Test
    void aSourceThatThrowsListsTheTitleWithNoSource() {
        Plan plan = TitlePickerRows.plan(List.of(), null, TitleConfig.getInstance(), id -> {
            throw new IllegalStateException("boom");
        });

        assertEquals(3, plan.notEarned().size());
        assertTrue(plan.notEarned().stream().allMatch(line -> line.source() == null));
    }

    @Test
    void atMostOneTitleIsShownAndShowingOneTakesTheOtherOff() {
        List<Tile> tiles = TitlePickerRows.plan(List.of("pumpkin_king", "hallows_eve_hallowed"),
                "pumpkin_king", TitleConfig.getInstance(), SOURCES).earned();

        assertEquals(List.of(new Tile("pumpkin_king", false), new Tile("hallows_eve_hallowed", true)),
                TitlePickerRows.showing(tiles, "Hallows_Eve_Hallowed"));
        assertEquals(List.of(new Tile("pumpkin_king", false), new Tile("hallows_eve_hallowed", false)),
                TitlePickerRows.showing(tiles, null), "Show none");
        assertEquals(List.of(new Tile("pumpkin_king", false), new Tile("hallows_eve_hallowed", false)),
                TitlePickerRows.showing(tiles, "keeper_of_lanterns"), "a title not earned is never shown");
    }

    @Test
    void aShownTitleThatIsNotEarnedOrSwitchedOffLeavesEveryTileOff() {
        assertEquals(List.of(new Tile("pumpkin_king", false)),
                TitlePickerRows.plan(List.of("pumpkin_king", "off"), "off", TitleConfig.getInstance(), SOURCES)
                        .earned());
        assertEquals(List.of(new Tile("pumpkin_king", false)),
                TitlePickerRows.plan(List.of("pumpkin_king"), "keeper_of_lanterns", TitleConfig.getInstance(),
                        SOURCES).earned());
    }
}
