package com.ziggfreed.common.commerce.fold;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;
import com.ziggfreed.common.commerce.InMemoryCommerceStore;
import com.ziggfreed.common.currency.CurrencyDef;
import com.ziggfreed.common.currency.CurrencyEngine;
import com.ziggfreed.common.currency.ItemWallet;
import com.ziggfreed.common.currency.asset.CurrencyAsset;
import com.ziggfreed.common.currency.asset.CurrencyConfig;
import com.ziggfreed.common.factor.FeatureFlags;
import com.ziggfreed.common.subject.Subject;

/**
 * A wallet whose feature reads off leaves every listing, still takes a credit, and is all there when
 * it comes back: what lets a seasonal currency carry over. The namespace is unique to this class.
 */
class WalletHideAxisTest {

    private static final String NAMESPACE = "hide_wallet";
    private static final Subject SUBJECT = Subject.of(UUID.randomUUID(), "Tester");
    /** Authored bounds-less on purpose: the form an author most often writes. */
    private static final String SEASONAL = "{ \"Cap\": 0, \"Requires\": { \"Factors\": [ "
            + "{ \"Factor\": \"hide_wallet:feature\", \"Param\": \"Spooky\" } ] } }";

    private AtomicBoolean spooky;

    @BeforeEach
    void declareTheFeature() {
        spooky = new AtomicBoolean(true);
        FeatureFlags.register(NAMESPACE, "spooky", "test", spooky::get);
        clearLayers();
    }

    @AfterEach
    void forget() {
        clearLayers();
        FeatureFlags.reset();
    }

    private static void clearLayers() {
        CurrencyConfig.getInstance().mergePackLayer(Map.of());
        CurrencyConfig.getInstance().mergeOwnerLayer(Map.of());
    }

    @Test
    @DisplayName("a hidden wallet leaves every listing but keeps earning, and its balance is all there when it returns")
    void aHiddenWalletKeepsItsBalance() throws IOException {
        seedWallets(SEASONAL);
        CurrencyEngine engine = CurrencyEngine.builder()
                .catalog(CommerceCatalogs.currencies())
                .items(ItemWallet.NONE)
                .store(new InMemoryCommerceStore())
                .warn(msg -> { })
                .build();
        engine.credit(SUBJECT, "spooky_sweets", 40);
        assertEquals(List.of("bounty_token", "spooky_sweets"), listedIds());

        spooky.set(false);

        assertEquals(List.of("bounty_token"), listedIds(), "off means absent from every listing");
        assertNotNull(CommerceCatalogs.currencies().get("spooky_sweets"), "but the wallet still resolves");
        engine.credit(SUBJECT, "spooky_sweets", 2);
        assertEquals(42L, engine.balance(SUBJECT, "spooky_sweets"), "a payout that lands late is kept");
        assertEquals(List.of("bounty_token", "spooky_sweets"), circulatingIds(),
                "the economy passes and the admin listing still walk it");

        spooky.set(true);

        assertEquals(List.of("bounty_token", "spooky_sweets"), listedIds());
        assertEquals(42L, engine.balance(SUBJECT, "spooky_sweets"), "unspent, it carries over");
    }

    @Test
    @DisplayName("Enabled false is still the owner's switch: out of every listing and out of circulation")
    void enabledFalseStillTakesTheWalletOutOfCirculation() throws IOException {
        seedWallets("{ \"Enabled\": false }");

        assertEquals(List.of("bounty_token"), listedIds());
        assertEquals(List.of("bounty_token"), circulatingIds());
        assertNull(CommerceCatalogs.currencies().get("spooky_sweets"),
                "a wallet taken out of circulation cannot be credited or charged");
    }

    // ==================== helpers ====================

    private static CurrencyAsset wallet(String id, String json) throws IOException {
        return CurrencyAsset.CODEC.decodeAndInheritJsonAsset(RawJsonReader.fromJsonString(json), null,
                new AssetExtraInfo<>(new AssetExtraInfo.Data(CurrencyAsset.class, id, null)));
    }

    private static void seedWallets(String sweetsJson) throws IOException {
        Map<String, CurrencyAsset> layer = new LinkedHashMap<>();
        layer.put("bounty_token", wallet("bounty_token", "{ \"Cap\": 0 }"));
        layer.put("spooky_sweets", wallet("spooky_sweets", sweetsJson));
        CurrencyConfig.getInstance().mergePackLayer(layer);
    }

    private static List<String> listedIds() {
        List<String> out = new ArrayList<>();
        for (CurrencyAsset asset : CurrencyConfig.getInstance().enabled()) {
            out.add(asset.getId());
        }
        return out;
    }

    private static List<String> circulatingIds() {
        List<String> out = new ArrayList<>();
        for (CurrencyDef def : CommerceCatalogs.currencies().all()) {
            out.add(def.id());
        }
        out.sort(Comparator.naturalOrder());
        return out;
    }
}
