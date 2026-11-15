package com.ziggfreed.common.loot.trigger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;

/**
 * A row's Group: rows of one group fold and rank among themselves (a hole still blocks its own group's broader
 * pattern), each group answers on its own, the shared group first, so a narrower or same-named row in another group
 * never takes a row's place; and a row's AddsYield reads true unless written false.
 */
class BonusRowGroupsTest {

    @AfterEach
    void reset() {
        BonusRowConfig.getInstance().mergePackLayer(Map.of());
    }

    private static BonusRowAsset row(String id, String json) throws IOException {
        AssetExtraInfo.Data data = new AssetExtraInfo.Data(BonusRowAsset.class, id, null);
        return BonusRowAsset.CODEC.decodeAndInheritJsonAsset(RawJsonReader.fromJsonString(json), null,
                new AssetExtraInfo<>(data));
    }

    private static final String PAYS = "\"Loot\": { \"Rolls\": [ { \"Grants\": { \"Commands\": [ \"say found\" ] } } ] }";

    private static List<String> idsFor(BonusMoment moment, String name) {
        return BonusRowConfig.getInstance().answers(moment, name).stream().map(BonusRow::sourceId).toList();
    }

    @Test
    void eachGroupAnswersItsOwnMostSpecificRowTheSharedGroupFirst() throws IOException {
        BonusRowConfig.getInstance().mergePackLayer(Map.of(
                "fixture_stone", row("fixture_stone", "{ \"When\": { \"Kind\": \"BreakBlock\", \"Match\": \"Rock_*\" }, "
                        + PAYS + " }"),
                "fixture_gem", row("fixture_gem", "{ \"Group\": \"Fixture_Finds\", \"When\": { \"Kind\": \"BreakBlock\","
                        + " \"Match\": \"*Rock_Gem_*\" }, " + PAYS + " }"),
                "fixture_pumpkin_a", row("fixture_pumpkin_a", "{ \"When\": { \"Kind\": \"PickupItem\","
                        + " \"Match\": \"Plant_Crop_Pumpkin_Item\" }, " + PAYS + " }"),
                "fixture_pumpkin_b", row("fixture_pumpkin_b", "{ \"Group\": \"fixture_finds\", \"When\": { \"Kind\":"
                        + " \"PickupItem\", \"Match\": \"Plant_Crop_Pumpkin_Item\" }, " + PAYS + " }")));

        assertEquals(List.of("fixture_stone", "fixture_gem"), idsFor(BonusMoment.BREAK_BLOCK, "Rock_Gem_Zephyr"),
                "the narrower row of another group never takes the shared row's place");
        assertEquals(List.of("fixture_stone"), idsFor(BonusMoment.BREAK_BLOCK, "Rock_Stone"));
        assertEquals("fixture_stone",
                BonusRowConfig.getInstance().bestFor(BonusMoment.BREAK_BLOCK, "Rock_Gem_Zephyr").sourceId(),
                "bestFor is the shared group's answer");
        assertEquals(List.of("fixture_pumpkin_a", "fixture_pumpkin_b"),
                idsFor(BonusMoment.PICKUP_ITEM, "Plant_Crop_Pumpkin_Item"),
                "one pattern in two groups is two answers, never one row taking the other's place (case ignored)");
    }

    @Test
    void aHoleBlocksOnlyItsOwnGroupsBroaderRow() throws IOException {
        BonusRowConfig.getInstance().mergePackLayer(Map.of(
                "fixture_ore", row("fixture_ore", "{ \"Group\": \"Fixture_Finds\", \"When\": { \"Kind\": \"BreakBlock\","
                        + " \"Match\": \"*Ore_Iron_*\" }, " + PAYS + " }"),
                "fixture_hole", row("fixture_hole", "{ \"Group\": \"Fixture_Finds\", \"When\": { \"Kind\":"
                        + " \"BreakBlock\", \"Match\": \"*Ore_Iron_Basalt_Cracked*\" } }"),
                "fixture_geode", row("fixture_geode", "{ \"When\": { \"Kind\": \"BreakBlock\", \"Match\": \"Ore_*\" }, "
                        + PAYS + " }")));

        List<BonusRow> cracked = BonusRowConfig.getInstance().answers(BonusMoment.BREAK_BLOCK, "Ore_Iron_Basalt_Cracked");
        assertEquals(List.of("fixture_geode", "fixture_hole"), cracked.stream().map(BonusRow::sourceId).toList());
        assertTrue(cracked.get(1).handsNothingOver(), "the hole answers its own group, and hands nothing over");
        assertEquals(List.of("fixture_geode", "fixture_ore"), idsFor(BonusMoment.BREAK_BLOCK, "Ore_Iron_Stone"));
    }

    @Test
    void aRowTakesTheMomentsYieldUnlessItSaysNot() throws IOException {
        BonusRowConfig.getInstance().mergePackLayer(Map.of(
                "fixture_luck", row("fixture_luck", "{ \"When\": { \"Kind\": \"BreakBlock\", \"Match\": \"Rock_*\" },"
                        + " \"Chance\": { \"Base\": 0 }, " + PAYS + " }"),
                "fixture_exact", row("fixture_exact", "{ \"When\": { \"Kind\": \"KillMob\", \"Match\": \"*\" },"
                        + " \"Chance\": { \"Base\": 2 }, \"AddsYield\": false, " + PAYS + " }")));

        assertTrue(BonusRowConfig.getInstance().bestFor(BonusMoment.BREAK_BLOCK, "Rock_Stone").addsYield());
        assertEquals(false, BonusRowConfig.getInstance().bestFor(BonusMoment.KILL_MOB, "Trork_Brute").addsYield());
    }
}
