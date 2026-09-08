package com.ziggfreed.common.ui.hud.bar;

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
 * The bar file's decode contract: every leaf reads back as written, every unauthored leaf answers its
 * documented default, a child under {@code Parent} keeps the leaves it did not restate, the source id
 * splits into the namespace the registry is keyed by and the local part a source is handed, and the
 * fold's source index answers the enabled bar for a source and nothing for a disabled one.
 */
class HudBarAssetCodecTest {

    static HudBarAsset bar(String json, String id, String parentId, HudBarAsset parent) throws IOException {
        AssetExtraInfo.Data data = new AssetExtraInfo.Data(HudBarAsset.class, id, parentId);
        return HudBarAsset.CODEC.decodeAndInheritJsonAsset(
                RawJsonReader.fromJsonString(json), parent, new AssetExtraInfo<>(data));
    }

    @AfterEach
    void clearFold() {
        HudBarConfig.getInstance().mergePackLayer(Map.of());
        HudBarConfig.getInstance().mergeOwnerLayer(Map.of());
    }

    @Test
    void everyLeafRoundTrips() throws Exception {
        HudBarAsset bar = bar("""
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
        assertEquals("mymod", bar.sourceNamespace());
        assertEquals("wood", bar.sourceLocalId());
        assertEquals("mymod.wood.name", bar.labelKey());
        assertNotNull(bar.icon());
        assertEquals("Tool_Hatchet_Crude", bar.icon().itemId());
        assertEquals("#6fbf73", bar.color());
        assertEquals(20, bar.order());
        assertEquals(2500L, bar.lingerMs());
        assertFalse(bar.enabled());
    }

    @Test
    void anEmptyFileAnswersEveryDocumentedDefault() throws Exception {
        HudBarAsset bar = bar("{}", "bare", null, null);

        assertNull(bar.source(), "no source means the bar never comes up");
        assertNull(bar.sourceNamespace());
        assertNull(bar.sourceLocalId());
        assertNull(bar.labelKey());
        assertNull(bar.icon());
        assertEquals(HudBarAsset.DEFAULT_COLOR, bar.color());
        assertEquals(HudBarAsset.DEFAULT_ORDER, bar.order());
        assertEquals(HudBarAsset.DEFAULT_LINGER_MS, bar.lingerMs());
        assertTrue(bar.enabled());
    }

    @Test
    void aNonPositiveLingerReadsAsTheDefault() throws Exception {
        assertEquals(HudBarAsset.DEFAULT_LINGER_MS, bar("{ \"LingerMs\": 0 }", "zero", null, null).lingerMs());
        assertEquals(HudBarAsset.DEFAULT_LINGER_MS, bar("{ \"LingerMs\": -5 }", "neg", null, null).lingerMs());
    }

    @Test
    void anIconGroupWithBothLeavesBlankReadsAsNoIcon() throws Exception {
        HudBarAsset bar = bar("{ \"Icon\": { \"ItemId\": \"\", \"TexturePath\": \" \" } }", "blankicon", null, null);
        assertNull(bar.icon());
    }

    @Test
    void aChildKeepsEveryLeafItDidNotRestate() throws Exception {
        HudBarAsset base = bar("""
                { "Source": "mymod:wood", "LabelKey": "mymod.wood.name",
                  "Icon": { "ItemId": "Tool_Hatchet_Crude" }, "Color": "#6fbf73", "Order": 20, "LingerMs": 2500 }
                """, "base", null, null);
        HudBarAsset child = bar("{ \"Color\": \"#ffffff\", \"Enabled\": false }", "child", "base", base);

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
    void theSourceIdSplitsAtItsFirstColonAndFoldsTheNamespace() throws Exception {
        HudBarAsset bar = bar("{ \"Source\": \"  MyMod:skill/WOOD:cutting \" }", "split", null, null);

        assertEquals("mymod", bar.sourceNamespace(), "the namespace is folded lower, so a registration matches by name");
        assertEquals("skill/WOOD:cutting", bar.sourceLocalId(), "the local part keeps its case and its own colons");

        assertNull(bar("{ \"Source\": \"nonamespace\" }", "none", null, null).sourceNamespace());
        assertNull(bar("{ \"Source\": \"mymod:\" }", "empty", null, null).sourceLocalId());
        assertNull(bar("{ \"Source\": \":wood\" }", "leading", null, null).sourceNamespace());
    }

    @Test
    void theFoldAnswersTheEnabledBarForASourceAndNothingForADisabledOne() throws Exception {
        HudBarAsset wood = bar("{ \"Source\": \"mymod:wood\", \"Order\": 20 }", "wood", null, null);
        HudBarAsset stone = bar("{ \"Source\": \"mymod:stone\", \"Enabled\": false }", "stone", null, null);
        HudBarAsset woodLater = bar("{ \"Source\": \"MYMOD:WOOD\", \"Order\": 30 }", "wood_two", null, null);
        HudBarConfig.getInstance().mergePackLayer(Map.of("wood", wood, "stone", stone, "wood_two", woodLater));

        assertSame(wood, HudBarConfig.getInstance().bySource("mymod:wood"),
                "two bars naming one source resolve to the one that sorts first by Order, then id");
        assertSame(wood, HudBarConfig.getInstance().bySource(" MyMod:Wood "),
                "the lookup is case-insensitive and trims, like every id in the fold");
        assertNull(HudBarConfig.getInstance().bySource("mymod:stone"), "a disabled bar answers for no source");
        assertNull(HudBarConfig.getInstance().bySource("mymod:nothing"));
        assertNull(HudBarConfig.getInstance().bySource(null));
    }

    @Test
    void anOwnerEntryOverTheSameIdWinsInTheIndex() throws Exception {
        HudBarAsset packWood = bar("{ \"Source\": \"mymod:wood\" }", "wood", null, null);
        HudBarConfig.getInstance().mergePackLayer(Map.of("wood", packWood));
        assertSame(packWood, HudBarConfig.getInstance().bySource("mymod:wood"));

        HudBarAsset ownerWood = bar("{ \"Enabled\": false }", "wood", "wood", packWood);
        HudBarConfig.getInstance().mergeOwnerLayer(Map.of("wood", ownerWood));
        assertNull(HudBarConfig.getInstance().bySource("mymod:wood"),
                "an owner switching a bar off takes it out of the index on the next lookup");
    }
}
