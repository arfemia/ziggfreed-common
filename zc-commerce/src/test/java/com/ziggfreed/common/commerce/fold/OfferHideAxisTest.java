package com.ziggfreed.common.commerce.fold;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;
import com.ziggfreed.common.factor.FeatureFlags;
import com.ziggfreed.common.progress.gate.GateSpec;
import com.ziggfreed.common.shop.ShopOffer;
import com.ziggfreed.common.shop.asset.ShopAssetStore;
import com.ziggfreed.common.shop.asset.ShopConfig;
import com.ziggfreed.common.shop.asset.ShopEntryAsset;
import com.ziggfreed.common.shop.asset.StorefrontAsset;

/**
 * An offer whose feature reads off, or that stands in a storefront that is not there, is off the
 * page and refuses a stale press; the admin view still names it. The namespace is unique to this
 * class.
 */
class OfferHideAxisTest {

    private static final String NAMESPACE = "hide_offer";
    private static final String FEATURE =
            "{ \"Factor\": \"hide_offer:feature\", \"Param\": \"Spooky\", \"Min\": 1 }";

    private AtomicBoolean spooky;

    @BeforeEach
    void declareTheFeature() {
        spooky = new AtomicBoolean(true);
        FeatureFlags.register(NAMESPACE, "spooky", "test", spooky::get);
        clearStores();
    }

    @AfterEach
    void forget() {
        clearStores();
        FeatureFlags.reset();
    }

    private static void clearStores() {
        ShopAssetStore.getInstance().mergeEntries(Map.of());
        ShopAssetStore.getInstance().mergeGenerators(Map.of());
        ShopConfig.getInstance().mergePackLayer(Map.of());
        ShopConfig.getInstance().mergeOwnerLayer(Map.of());
        CommerceCatalogs.installAxisValues(null);
        CommerceCatalogs.refreshShops();
    }

    @Test
    @DisplayName("an offer whose feature is off leaves the page and refuses a stale press; the admin view still names it")
    void aHiddenOfferLeavesThePage() throws IOException {
        Map<String, ShopEntryAsset> layer = new LinkedHashMap<>();
        layer.put("lantern_bomb", offer("lantern_bomb",
                "{ \"Shop\": \"General\", \"Requires\": { \"Factors\": [ " + FEATURE + " ] } }"));
        layer.put("boost", offer("boost", "{ \"Shop\": \"General\" }"));
        seedOffers(layer);
        ShopOffer bomb = CommerceCatalogs.shops().offer("lantern_bomb");
        assertNotNull(bomb);
        assertTrue(bomb.enabled());
        assertEquals(List.of("boost", "lantern_bomb"), onThePage("general"));

        spooky.set(false);

        assertFalse(bomb.enabled(), "a press on a page drawn before the switch refuses as not available");
        assertEquals(List.of("boost"), onThePage("general"), "off means absent, not locked");
        assertEquals(2, CommerceCatalogs.shopContent().offersOf("general").size(),
                "the admin listing still names it, as switched off");
    }

    @Test
    @DisplayName("a switched-off offer leaves the page too, as its schema always said")
    void aSwitchedOffOfferLeavesThePage() throws IOException {
        Map<String, ShopEntryAsset> layer = new LinkedHashMap<>();
        layer.put("retired", offer("retired", "{ \"Shop\": \"General\", \"Enabled\": false }"));
        layer.put("boost", offer("boost", "{ \"Shop\": \"General\" }"));
        seedOffers(layer);

        assertEquals(List.of("boost"), onThePage("general"));
    }

    @Test
    @DisplayName("an offer's purchase gate is its Requires block with the feature taken out")
    void theOffersGateIsItsLock() throws IOException {
        seedOffers(Map.of("lantern_bomb", offer("lantern_bomb", "{ \"Shop\": \"General\","
                + " \"Requires\": { \"Factors\": [ " + FEATURE + " ], \"Permission\": \"shop.vip\" } }")));
        ShopOffer bomb = CommerceCatalogs.shops().offer("lantern_bomb");
        assertNotNull(bomb);

        GateSpec gate = bomb.requires();

        assertNotNull(gate);
        assertEquals(0, gate.factorsOrEmpty().length, "a live feature is never a lock reason");
        assertEquals("shop.vip", gate.getPermission());
    }

    @Test
    @DisplayName("every offer of a storefront that is not there is off, whatever its own file says")
    void aHiddenStorefrontTakesItsOffersWithIt() throws IOException {
        ShopConfig.getInstance().mergePackLayer(Map.of("spooky_stall", storefront("spooky_stall",
                "{ \"Requires\": { \"Factors\": [ " + FEATURE + " ] } }")));
        Map<String, ShopEntryAsset> layer = new LinkedHashMap<>();
        layer.put("candy", offer("candy", "{ \"Shop\": \"Spooky_Stall\" }"));
        layer.put("orphan", offer("orphan", "{ \"Shop\": \"Nowhere\" }"));
        seedOffers(layer);
        ShopOffer candy = CommerceCatalogs.shops().offer("candy");
        ShopOffer orphan = CommerceCatalogs.shops().offer("orphan");
        assertNotNull(candy);
        assertNotNull(orphan);
        assertTrue(candy.enabled());

        spooky.set(false);

        assertFalse(candy.enabled(), "a stale press at a storefront that is gone sells nothing");
        assertTrue(orphan.enabled(),
                "a storefront nothing defines is the audit's finding, never a refusal here");
    }

    @Test
    @DisplayName("an offer carries its storefront's lock, read live, with the storefront's feature taken out")
    void anOfferCarriesItsStorefrontsLock() throws IOException {
        ShopConfig.getInstance().mergePackLayer(Map.of(
                "vip_stall", storefront("vip_stall", "{ \"Requires\": { \"Factors\": [ " + FEATURE + " ],"
                        + " \"Permission\": \"shop.vip\" } }"),
                "general", storefront("general", "{}")));
        Map<String, ShopEntryAsset> layer = new LinkedHashMap<>();
        layer.put("candy", offer("candy", "{ \"Shop\": \"Vip_Stall\" }"));
        layer.put("boost", offer("boost", "{ \"Shop\": \"General\" }"));
        layer.put("orphan", offer("orphan", "{}"));
        seedOffers(layer);
        ShopOffer candy = CommerceCatalogs.shops().offer("candy");
        ShopOffer boost = CommerceCatalogs.shops().offer("boost");
        ShopOffer orphan = CommerceCatalogs.shops().offer("orphan");
        assertNotNull(candy);
        assertNotNull(boost);
        assertNotNull(orphan);

        GateSpec lock = candy.storefrontRequires();

        assertNotNull(lock);
        assertEquals(0, lock.factorsOrEmpty().length, "the storefront's feature decides presence only");
        assertEquals("shop.vip", lock.getPermission());
        assertNull(boost.storefrontRequires(), "a storefront asking nothing locks nothing");
        assertNull(orphan.storefrontRequires(), "an offer on no storefront has none to ask");
    }

    // ==================== helpers ====================

    private static ShopEntryAsset offer(String id, String json) throws IOException {
        return ShopEntryAsset.CODEC.decodeAndInheritJsonAsset(RawJsonReader.fromJsonString(json), null,
                new AssetExtraInfo<>(new AssetExtraInfo.Data(ShopEntryAsset.class, id, null)));
    }

    private static StorefrontAsset storefront(String id, String json) throws IOException {
        return StorefrontAsset.CODEC.decodeAndInheritJsonAsset(RawJsonReader.fromJsonString(json), null,
                new AssetExtraInfo<>(new AssetExtraInfo.Data(StorefrontAsset.class, id, null)));
    }

    private static void seedOffers(Map<String, ShopEntryAsset> layer) {
        ShopAssetStore.getInstance().mergeEntries(layer);
        CommerceCatalogs.refreshShops();
    }

    /** The offer ids the storefront page lists, sorted, since the catalogue keeps no order. */
    private static List<String> onThePage(String shopId) {
        List<String> out = new ArrayList<>();
        for (ShopEntryOffer offer : CommerceCatalogs.shopContent().availableOffersOf(shopId)) {
            out.add(offer.offerId());
        }
        out.sort(Comparator.naturalOrder());
        return out;
    }
}
