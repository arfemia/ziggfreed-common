package com.ziggfreed.common.ui.hud.panel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;

/**
 * The override file's decode contract: every authored leaf reads back as written, every unauthored
 * leaf reads null so it changes nothing about the row, a child under {@code Parent} keeps the
 * leaves it did not restate, the file's leaves fold into a display, and the fold's row index
 * answers the override authored for a row, a switched-off one included, and nothing for a row
 * nobody wrote about.
 */
class HudRowAssetCodecTest {

    static HudRowAsset bar(String json, String id, String parentId, HudRowAsset parent) throws IOException {
        AssetExtraInfo.Data data = new AssetExtraInfo.Data(HudRowAsset.class, id, parentId);
        return HudRowAsset.CODEC.decodeAndInheritJsonAsset(
                RawJsonReader.fromJsonString(json), parent, new AssetExtraInfo<>(data));
    }

    @AfterEach
    void clearFold() {
        HudRowConfig.getInstance().mergePackLayer(Map.of());
        HudRowConfig.getInstance().mergeOwnerLayer(Map.of());
    }

    @Test
    void everyLeafRoundTrips() throws Exception {
        HudRowAsset bar = bar("""
                { "Source": "mymod:wood",
                  "LabelKey": "mymod.wood.name",
                  "Icon": { "ItemId": "Tool_Hatchet_Crude" },
                  "Color": "#6fbf73",
                  "Order": 20,
                  "LingerMs": 2500,
                  "Enabled": false }
                """, "Mymod_Wood", null, null);

        assertEquals("Mymod_Wood", bar.getId(), "the id is the file name as written; the fold lower-cases it at merge");
        assertEquals("mymod:wood", bar.source());
        assertEquals("mymod.wood.name", bar.labelKey());
        assertNotNull(bar.icon());
        assertEquals("Tool_Hatchet_Crude", bar.icon().itemId());
        assertEquals("#6fbf73", bar.color());
        assertEquals(20, bar.order());
        assertEquals(2500L, bar.lingerMs());
        assertFalse(bar.enabled());
    }

    @Test
    void anEmptyFileOverridesNothing() throws Exception {
        HudRowAsset bar = bar("{}", "bare", null, null);

        assertNull(bar.source(), "no source means the file applies to no row");
        assertNull(bar.labelKey());
        assertNull(bar.icon());
        assertNull(bar.color(), "an unauthored colour keeps the reporting mod's");
        assertNull(bar.order(), "an unauthored order keeps the reporting mod's");
        assertNull(bar.lingerMs(), "an unauthored linger keeps the reporting mod's");
        assertTrue(bar.enabled());

        HudRowDisplay display = bar.display();
        assertNull(display.label());
        assertNull(display.icon());
        assertNull(display.color());
        assertNull(display.order());
        assertNull(display.lingerMs());
    }

    @Test
    void aNonPositiveLingerOverridesNothing() throws Exception {
        assertNull(bar("{ \"LingerMs\": 0 }", "zero", null, null).lingerMs());
        assertNull(bar("{ \"LingerMs\": -5 }", "neg", null, null).lingerMs());
    }

    @Test
    void anIconGroupWithBothLeavesBlankReadsAsNoIcon() throws Exception {
        HudRowAsset bar = bar("{ \"Icon\": { \"ItemId\": \"\", \"TexturePath\": \" \" } }", "blankicon", null, null);
        assertNull(bar.icon());
    }

    @Test
    void theFilesLeavesFoldIntoADisplay() throws Exception {
        HudRowAsset bar = bar("""
                { "Source": "mymod:wood", "LabelKey": "mymod.wood.name",
                  "Icon": { "TexturePath": "UI/Wood.png" }, "Color": "#6fbf73", "Order": 20, "LingerMs": 2500 }
                """, "wood", null, null);

        HudRowDisplay display = bar.display();
        assertNotNull(display.label(), "an authored label key becomes a client-resolved message");
        assertNotNull(display.icon());
        assertEquals("UI/Wood.png", display.icon().texturePath());
        assertEquals("#6fbf73", display.color());
        assertEquals(20, display.order());
        assertEquals(2500L, display.lingerMs());
    }

    @Test
    void aChildKeepsEveryLeafItDidNotRestate() throws Exception {
        HudRowAsset base = bar("""
                { "Source": "mymod:wood", "LabelKey": "mymod.wood.name",
                  "Icon": { "ItemId": "Tool_Hatchet_Crude" }, "Color": "#6fbf73", "Order": 20, "LingerMs": 2500 }
                """, "base", null, null);
        HudRowAsset child = bar("{ \"Color\": \"#ffffff\", \"Enabled\": false }", "child", "base", base);

        assertEquals("#ffffff", child.color(), "the restated leaf is the child's");
        assertFalse(child.enabled());
        assertEquals("mymod:wood", child.source(), "an unrestated leaf is inherited");
        assertEquals("mymod.wood.name", child.labelKey());
        assertNotNull(child.icon());
        assertEquals("Tool_Hatchet_Crude", child.icon().itemId());
        assertEquals(20, child.order());
        assertEquals(2500L, child.lingerMs());
    }

    @Test
    void theFoldAnswersTheOverrideForARowSwitchedOffOrNot() throws Exception {
        HudRowAsset wood = bar("{ \"Source\": \"mymod:wood\", \"Order\": 20 }", "wood", null, null);
        HudRowAsset stone = bar("{ \"Source\": \"mymod:stone\", \"Enabled\": false }", "stone", null, null);
        HudRowAsset woodLater = bar("{ \"Source\": \"MYMOD:WOOD\", \"Order\": 30 }", "wood_two", null, null);
        HudRowAsset plank = bar("{ \"Source\": \"item:Wood_Plank\", \"LingerMs\": 9000 }", "plank", null, null);
        HudRowConfig.getInstance().mergePackLayer(
                Map.of("wood", wood, "stone", stone, "wood_two", woodLater, "plank", plank));

        assertSame(wood, HudRowConfig.getInstance().bySource("mymod:wood"),
                "two files naming one row resolve to the one that sorts first by Order, then id");
        assertSame(wood, HudRowConfig.getInstance().bySource(" MyMod:Wood "),
                "the lookup is case-insensitive and trims, like every id in the fold");
        assertSame(stone, HudRowConfig.getInstance().bySource("mymod:stone"),
                "a switched-off override is still answered, so the panel can keep the row off");
        assertFalse(HudRowConfig.getInstance().bySource("mymod:stone").enabled());
        assertSame(plank, HudRowConfig.getInstance().bySource(HudPanels.itemRowId("Wood_Plank")),
                "an item row is overridden by the id it is counted under");
        assertNull(HudRowConfig.getInstance().bySource("mymod:nothing"),
                "a row nobody wrote about has no override and reads exactly what its mod said");
        assertNull(HudRowConfig.getInstance().bySource(null));
    }

    @Test
    void anOwnerEntryOverTheSameIdWinsInTheIndex() throws Exception {
        HudRowAsset packWood = bar("{ \"Source\": \"mymod:wood\", \"Color\": \"#6fbf73\" }", "wood", null, null);
        HudRowConfig.getInstance().mergePackLayer(Map.of("wood", packWood));
        assertSame(packWood, HudRowConfig.getInstance().bySource("mymod:wood"));

        HudRowAsset ownerWood = bar("{ \"Enabled\": false }", "wood", "wood", packWood);
        HudRowConfig.getInstance().mergeOwnerLayer(Map.of("wood", ownerWood));
        HudRowAsset answered = HudRowConfig.getInstance().bySource("mymod:wood");
        assertSame(ownerWood, answered, "the owner's entry is the one the index answers on the next lookup");
        assertFalse(answered.enabled(), "an owner switching a row off");
        assertEquals("#6fbf73", answered.color(), "over the pack's leaves it did not restate");
    }

    @Test
    void anOwnerEntryWithNoPackParentStandsOnItsOwn() throws Exception {
        HudRowAsset ownerOnly = bar("{ \"Source\": \"mymod:wood\", \"LingerMs\": 8000 }", "slow_wood", null, null);
        HudRowConfig.getInstance().mergeOwnerLayer(Map.of("slow_wood", ownerOnly));

        assertSame(ownerOnly, HudRowConfig.getInstance().bySource("mymod:wood"),
                "an owner retunes a row nobody shipped a file for by naming it in Source");
    }
}
