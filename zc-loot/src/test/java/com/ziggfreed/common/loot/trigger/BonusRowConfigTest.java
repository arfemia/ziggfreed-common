package com.ziggfreed.common.loot.trigger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ziggfreed.common.loot.LootGrants;
import com.ziggfreed.common.loot.LootRef;
import com.ziggfreed.common.loot.Roll;

/** The library's rows folded from every layer, and an owner's last word on them. */
class BonusRowConfigTest {

    @TempDir
    Path ownerDir;

    @BeforeEach
    void pointTheOwnerFileHere() {
        BonusRowOwnerLayers.setDirectory(ownerDir);
    }

    @AfterEach
    void reset() {
        BonusRowConfig config = BonusRowConfig.getInstance();
        config.mergePackLayer(Map.of());
        config.mergeOwnerLayer(Map.of());
        BonusRowOwnerLayers.setDirectory(BonusRowOwnerLayers.DEFAULT_DIRECTORY);
    }

    private static LootRef paying() {
        return LootRef.of(null, new Roll[] {Roll.of(null, null, null, null, LootGrants.ofItem("Fixture_Gem", 1), null)});
    }

    private static String idFor(BonusMoment moment, String name) {
        BonusRow row = BonusRowConfig.getInstance().bestFor(moment, name);
        return row == null ? null : row.sourceId();
    }

    private static void owner(String json) throws IOException {
        Files.writeString(BonusRowOwnerLayers.file(), json, StandardCharsets.UTF_8);
        BonusRowOwnerLayers.reload();
    }

    @Test
    void aFoldedRowAnswersItsMomentByItsPatternUnderItsLowerCasedId() {
        BonusRowConfig.getInstance().mergePackLayer(Map.of(
                "Fixture_Geode", BonusRowAsset.of(BonusMoment.BREAK_BLOCK, "*Geode*", null, paying(), null)));

        assertEquals("fixture_geode", idFor(BonusMoment.BREAK_BLOCK, "Rock_Geode_Cursed"));
        assertNull(idFor(BonusMoment.PICKUP_ITEM, "Rock_Geode_Cursed"), "a row answers its own moment only");
    }

    @Test
    void aSwitchedOffRowLeavesTheTableAndARowNamingNoMomentIsRecorded() {
        BonusRowConfig.getInstance().mergePackLayer(Map.of(
                "fixture_off", BonusRowAsset.of(BonusMoment.BREAK_BLOCK, "*", null, paying(), false),
                "fixture_nomoment", BonusRowAsset.of(null, "*", null, paying(), null)));

        assertTrue(BonusRowConfig.getInstance().table().entries().isEmpty());
        assertEquals(Map.of("fixture_nomoment", ""), BonusRowConfig.getInstance().momentlessRows());
    }

    @Test
    void twoRowsClaimingOnePatternResolveToTheIdThatSortsLast() {
        // The config's layers are hash maps, and these ids iterate there in the reverse of sorted
        // order (fixture_y first) at every table size, so fixture_y wins only through the fold's
        // sort. Ids that already iterate sorted (fixture_a, fixture_b) pass with the sort deleted.
        BonusRowConfig.getInstance().mergePackLayer(Map.of(
                "fixture_y", BonusRowAsset.of(BonusMoment.KILL_MOB, "Skeleton_*", null, paying(), null),
                "fixture_x", BonusRowAsset.of(BonusMoment.KILL_MOB, "skeleton_*", null, paying(), null)));

        assertEquals("fixture_y", idFor(BonusMoment.KILL_MOB, "Skeleton_Burnt_Archer"),
                "a stable answer whatever order the packs loaded in");
        assertEquals(1, BonusRowConfig.getInstance().table().size());
    }

    @Test
    void aRowWithNoLootIsAHoleThatStillBlocksTheBroaderPattern() {
        BonusRowConfig.getInstance().mergePackLayer(Map.of(
                "fixture_rock", BonusRowAsset.of(BonusMoment.BREAK_BLOCK, "Rock_*", null, paying(), null),
                "fixture_geode", BonusRowAsset.of(BonusMoment.BREAK_BLOCK, "Rock_Geode*", null, LootRef.of(null, null), null)));

        BonusRow row = BonusRowConfig.getInstance().bestFor(BonusMoment.BREAK_BLOCK, "Rock_Geode_Cursed");
        assertNotNull(row);
        assertEquals("fixture_geode", row.sourceId());
        assertTrue(row.handsNothingOver(), "the hole answers, and it hands nothing over");
    }

    @Test
    void anOwnerSwitchingARowOffLetsTheBroaderPatternCoverTheNameAgain() throws IOException {
        BonusRowConfig.getInstance().mergePackLayer(Map.of(
                "fixture_rock", BonusRowAsset.of(BonusMoment.BREAK_BLOCK, "Rock_*", null, paying(), null),
                "fixture_geode", BonusRowAsset.of(BonusMoment.BREAK_BLOCK, "Rock_Geode*", null, paying(), null)));
        assertEquals("fixture_geode", idFor(BonusMoment.BREAK_BLOCK, "Rock_Geode_Cursed"));

        owner("{ \"Fixture_Geode\": { \"Enabled\": false } }");

        assertEquals("fixture_rock", idFor(BonusMoment.BREAK_BLOCK, "Rock_Geode_Cursed"));
    }

    @Test
    void anOwnerEntryRetunesOneLeafAndAnUnknownIdStandsAlone() throws IOException {
        BonusRowConfig.getInstance().mergePackLayer(Map.of(
                "fixture_pumpkin", BonusRowAsset.of(BonusMoment.PICKUP_ITEM, "*Pumpkin*", null, paying(), null)));

        owner("""
                { "$SchemaVersion": 1,
                  "fixture_pumpkin": { "Chance": { "Base": 15.0 } },
                  "fixture_owner_bones": { "When": { "Kind": "KillMob", "Match": "Skeleton_*" },
                                           "Loot": { "Rolls": [ { "Grants": { "Commands": [ "say bones" ] } } ] } } }
                """);

        BonusRow pumpkin = BonusRowConfig.getInstance().bestFor(BonusMoment.PICKUP_ITEM, "Plant_Crop_Pumpkin_Item");
        assertNotNull(pumpkin);
        assertEquals(15.0, pumpkin.chance().getBase(), 1e-9);
        assertEquals("*Pumpkin*", pumpkin.match(), "the leaves the owner left alone are the pack's");
        assertEquals("fixture_owner_bones", idFor(BonusMoment.KILL_MOB, "Skeleton_Burnt_Archer"));
    }
}
