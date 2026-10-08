package com.ziggfreed.common.commerce.fold;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;
import com.ziggfreed.common.board.asset.BoardAsset;
import com.ziggfreed.common.board.asset.BoardConfig;
import com.ziggfreed.common.progress.gate.GatedContent;
import com.ziggfreed.common.shop.asset.ShopAssetStore;
import com.ziggfreed.common.shop.asset.ShopConfig;
import com.ziggfreed.common.shop.asset.ShopEntryAsset;
import com.ziggfreed.common.shop.asset.StorefrontAsset;

/**
 * What commerce keeps behind a requirement, as {@link GatedContent} lists it: a switched-on offer whose own lock
 * asks for something (named by its title, pictured by its own icon, placed at its storefront), never an offer
 * that asks nothing, one switched off or one in a storefront switched off; and a board slot whose Requires
 * opens one more posting of its band, placed at its board, never a slot that asks nothing.
 */
class CommerceGatedContentTest {

    private static final String RANK =
            "{ \"Factor\": \"ziggfreedcommon:reputation_rank\", \"Param\": \"Test_Rep/Revered\", \"Min\": 1 }";

    @BeforeEach
    @AfterEach
    void clearStores() {
        ShopAssetStore.getInstance().mergeEntries(Map.of());
        ShopAssetStore.getInstance().mergeGenerators(Map.of());
        ShopConfig.getInstance().mergePackLayer(Map.of());
        ShopConfig.getInstance().mergeOwnerLayer(Map.of());
        BoardConfig.getInstance().mergePackLayer(Map.of());
        BoardConfig.getInstance().mergeOwnerLayer(Map.of());
        CommerceCatalogs.installAxisValues(null);
        CommerceCatalogs.refreshShops();
    }

    @Test
    void onlyASwitchedOnOfferWithALockIsListedNamedPicturedAndPlaced() throws IOException {
        Map<String, StorefrontAsset> shops = new LinkedHashMap<>();
        shops.put("stall", storefront("stall", "{ \"Text\": { \"TitleKey\": \"test.stall.title\" } }"));
        shops.put("shut", storefront("shut", "{ \"Enabled\": false }"));
        ShopConfig.getInstance().mergePackLayer(shops);
        Map<String, ShopEntryAsset> layer = new LinkedHashMap<>();
        layer.put("helm", offer("helm", "{ \"Shop\": \"Stall\", \"Icon\": \"Test_Helm\", "
                + "\"Text\": { \"TitleKey\": \"test.helm.name\" }, \"Requires\": { \"Factors\": [ " + RANK + " ] } }"));
        layer.put("open", offer("open", "{ \"Shop\": \"Stall\", \"Text\": { \"TitleKey\": \"test.open.name\" } }"));
        layer.put("off", offer("off", "{ \"Shop\": \"Stall\", \"Enabled\": false, "
                + "\"Text\": { \"TitleKey\": \"test.off.name\" }, \"Requires\": { \"Factors\": [ " + RANK + " ] } }"));
        layer.put("elsewhere", offer("elsewhere", "{ \"Shop\": \"Shut\", "
                + "\"Text\": { \"TitleKey\": \"test.elsewhere.name\" }, \"Requires\": { \"Factors\": [ " + RANK + " ] } }"));
        layer.put("anywhere", offer("anywhere", "{ \"Text\": { \"DisplayName\": \"Anywhere\" }, "
                + "\"Requires\": { \"AnyOf\": [ { \"Factors\": [ " + RANK + " ] } ] } }"));
        ShopAssetStore.getInstance().mergeEntries(layer);
        CommerceCatalogs.refreshShops();

        List<GatedContent.Entry> entries = CommerceGatedContent.entries();
        assertEquals(2, entries.size(), "an open offer, a switched-off one and one in a shut storefront are left out");
        GatedContent.Entry anywhere = entries.stream().filter(e -> e.place() == null).findFirst().orElseThrow();
        assertEquals("Anywhere", anywhere.name().getRawText(), "a display name stands in for a missing key");
        GatedContent.Entry helm = entries.stream().filter(e -> e.place() != null).findFirst().orElseThrow();
        assertTrue(helm.name().getMessageId().endsWith("test.helm.name"), helm.name().getMessageId());
        assertEquals("Test_Helm", helm.iconItemId());
        assertTrue(helm.place().getMessageId().endsWith("test.stall.title"), "placed at its storefront");
        assertEquals(1, GatedContent.positiveFactors(helm.requires()).size(), "the lock it carries is the offer's own");
    }

    @Test
    void aBoardSlotWithARequirementOpensOneMorePostingOfItsBand() throws IOException {
        BoardConfig.getInstance().mergePackLayer(Map.of("jobs", board("jobs", """
                { "Text": { "TitleKey": "test.jobs.title" }, "Icon": "Test_Board",
                  "Slots": [ { "Difficulty": "Night" },
                             { "Difficulty": "Night", "Optional": true, "Requires": { "Factors": [ %s ] } } ] }
                """.formatted(RANK))));
        List<GatedContent.Entry> entries = CommerceGatedContent.entries();
        assertEquals(1, entries.size(), "the slot that asks nothing is not listed");
        GatedContent.Entry slot = entries.get(0);
        assertEquals(CommerceGatedContent.EXTRA_SLOT_KEY, slot.name().getMessageId());
        assertEquals("Test_Board", slot.iconItemId());
        assertFalse(slot.showsItem(), "the board's picture only stands for the slot, so it carries no item tooltip");
        assertTrue(slot.place().getMessageId().endsWith("test.jobs.title"), "placed at its board");
    }

    private static ShopEntryAsset offer(String id, String json) throws IOException {
        return ShopEntryAsset.CODEC.decodeAndInheritJsonAsset(RawJsonReader.fromJsonString(json), null,
                new AssetExtraInfo<>(new AssetExtraInfo.Data(ShopEntryAsset.class, id, null)));
    }

    private static StorefrontAsset storefront(String id, String json) throws IOException {
        return StorefrontAsset.CODEC.decodeAndInheritJsonAsset(RawJsonReader.fromJsonString(json), null,
                new AssetExtraInfo<>(new AssetExtraInfo.Data(StorefrontAsset.class, id, null)));
    }

    private static BoardAsset board(String id, String json) throws IOException {
        return BoardAsset.CODEC.decodeAndInheritJsonAsset(RawJsonReader.fromJsonString(json), null,
                new AssetExtraInfo<>(new AssetExtraInfo.Data(BoardAsset.class, id, null)));
    }
}
