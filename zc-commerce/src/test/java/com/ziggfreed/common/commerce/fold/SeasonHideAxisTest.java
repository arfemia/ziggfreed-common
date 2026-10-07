package com.ziggfreed.common.commerce.fold;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.assetstore.JsonAsset;
import com.hypixel.hytale.assetstore.codec.AssetBuilderCodec;
import com.hypixel.hytale.codec.util.RawJsonReader;
import com.ziggfreed.common.board.asset.BoardAsset;
import com.ziggfreed.common.board.asset.BoardAssetStore;
import com.ziggfreed.common.board.asset.BoardConfig;
import com.ziggfreed.common.board.asset.BoardValidator;
import com.ziggfreed.common.board.asset.BountyAsset;
import com.ziggfreed.common.currency.asset.CurrencyAsset;
import com.ziggfreed.common.currency.asset.CurrencyConfig;
import com.ziggfreed.common.factor.FeatureFlags;
import com.ziggfreed.common.progress.gate.GateSpec;
import com.ziggfreed.common.quest.asset.QuestDefinition;
import com.ziggfreed.common.season.SeasonGate;
import com.ziggfreed.common.shop.asset.ShopAssetStore;
import com.ziggfreed.common.shop.asset.ShopConfig;
import com.ziggfreed.common.shop.asset.ShopEntryAsset;
import com.ziggfreed.common.shop.asset.ShopValidator;
import com.ziggfreed.common.shop.asset.StorefrontAsset;
import com.ziggfreed.common.validation.Finding;
import com.ziggfreed.common.validation.Severity;

/**
 * A {@code Season} on each commerce store HIDES, read live, and never locks: out of season an offer,
 * a storefront, a board, a wallet and a contract are each absent, in season each behaves as if the
 * leaf were not written, and its lock is only what its own {@code Requires} says. The audit names an
 * id no calendar event declares, in each domain's own name.
 */
class SeasonHideAxisTest {

    private static final String RANK = "{ \"Factor\": \"yourmod:rank\", \"Min\": 5 }";

    private AtomicBoolean harvestLive;

    @BeforeEach
    void declareTheCalendarsSwitch() {
        harvestLive = new AtomicBoolean(false);
        FeatureFlags.register(SeasonGate.NAMESPACE, "Harvest_Feast_Live", "test", harvestLive::get);
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
        BoardConfig.getInstance().mergePackLayer(Map.of());
        BoardConfig.getInstance().mergeOwnerLayer(Map.of());
        BoardAssetStore.getInstance().merge(Map.of());
        CurrencyConfig.getInstance().mergePackLayer(Map.of());
        CurrencyConfig.getInstance().mergeOwnerLayer(Map.of());
    }

    private static <T extends JsonAsset<String>> T decode(AssetBuilderCodec<String, T> codec, Class<T> type,
            String id, String json) throws IOException {
        return codec.decodeAndInheritJsonAsset(RawJsonReader.fromJsonString(json), null,
                new AssetExtraInfo<>(new AssetExtraInfo.Data(type, id, null)));
    }

    @Test
    void anOfferOutOfSeasonIsOffThePageAndItsLockIsOnlyWhatItWrote() throws IOException {
        ShopEntryAsset pie = decode(ShopEntryAsset.CODEC, ShopEntryAsset.class, "harvest_feast_pie",
                "{ \"Shop\": \"Harvest_Feast_Stall\", \"Season\": \"Harvest_Feast\" }");
        ShopEntryAsset ranked = decode(ShopEntryAsset.CODEC, ShopEntryAsset.class, "harvest_feast_roast",
                "{ \"Season\": \"Harvest_Feast\", \"Requires\": { \"Factors\": [ " + RANK + " ] } }");

        assertEquals("Harvest_Feast", pie.getSeason());
        assertFalse(pie.isAvailable(), "out of season the offer is not on this server");
        assertNull(pie.lockRequires(), "and nothing about it is a lock");
        assertFalse(ranked.isAvailable());
        harvestLive.set(true);
        assertTrue(pie.isAvailable(), "in season, read live with no refresh");
        GateSpec lock = ranked.lockRequires();
        assertNotNull(lock);
        assertEquals("yourmod:rank", lock.factorsOrEmpty()[0].getFactor(), "the lock is what the file wrote");
    }

    @Test
    void aStorefrontOutOfSeasonIsNotThere() throws IOException {
        StorefrontAsset stall = decode(StorefrontAsset.CODEC, StorefrontAsset.class, "harvest_feast_stall",
                "{ \"Season\": \"Harvest_Feast\" }");

        assertFalse(stall.isAvailable());
        assertNull(stall.lockRequires());
        harvestLive.set(true);
        assertTrue(stall.isAvailable());
    }

    @Test
    void aBoardOutOfSeasonIsNotThere() throws IOException {
        BoardAsset board = decode(BoardAsset.CODEC, BoardAsset.class, "harvest_feast_board",
                "{ \"Season\": \"Harvest_Feast\", \"Slots\": [ { \"Count\": 1 } ] }");

        assertFalse(board.isAvailable());
        assertNull(board.lockRequires(), "a season is never the board's accept gate");
        harvestLive.set(true);
        assertTrue(board.isAvailable());
    }

    @Test
    void aWalletOutOfSeasonIsNotListed() throws IOException {
        CurrencyAsset token = decode(CurrencyAsset.CODEC, CurrencyAsset.class, "harvest_feast_token",
                "{ \"Season\": \"Harvest_Feast\" }");

        assertFalse(token.isListed(), "out of every listing, its balances kept");
        harvestLive.set(true);
        assertTrue(token.isListed());
    }

    @Test
    void aContractOutOfSeasonIsNeverPostedNorOfferedAndLocksNothing() throws IOException {
        BountyAsset hunt = decode(BountyAsset.CODEC, BountyAsset.class, "harvest_feast_turkey_hunt",
                "{ \"Season\": \"Harvest_Feast\", \"Boards\": [ { \"Board\": \"Harvest_Feast_Board\","
                        + " \"Difficulty\": \"Normal\" } ] }");
        QuestDefinition folded = hunt.toDefinition(null);

        assertFalse(hunt.isAvailable(), "the draw never posts it");
        assertFalse(folded.quest().available(), "and the runtime never offers it");
        assertTrue(folded.requires().isEmpty(), "a season is no lock reason");
        harvestLive.set(true);
        assertTrue(hunt.isAvailable());
        assertTrue(folded.quest().available(), "the same folded object reads the run");
    }

    @Test
    void theAuditNamesASeasonNoEventDeclaresInEachDomainsOwnName() throws IOException {
        ShopAssetStore.getInstance().mergeEntries(Map.of("harvest_feast_pie", decode(ShopEntryAsset.CODEC,
                ShopEntryAsset.class, "harvest_feast_pie", "{ \"Season\": \"Harvest_Faest\" }")));
        BoardConfig.getInstance().mergePackLayer(Map.of("harvest_feast_board", decode(BoardAsset.CODEC,
                BoardAsset.class, "harvest_feast_board", "{ \"Season\": \"Harvest_Faest\" }")));
        CurrencyConfig.getInstance().mergePackLayer(Map.of("harvest_feast_token", decode(CurrencyAsset.CODEC,
                CurrencyAsset.class, "harvest_feast_token", "{ \"Season\": \"Harvest_Feast\" }")));

        List<Finding> found = CommerceAudit.auditSeasons();

        assertEquals(2, found.size(), found.toString());
        for (Finding finding : found) {
            assertEquals(SeasonGate.UNKNOWN_SEASON, finding.code());
            assertEquals(Severity.WARNING, finding.severity());
        }
        assertEquals(List.of(ShopValidator.DOMAIN, BoardValidator.DOMAIN),
                found.stream().map(Finding::domain).toList(), "each in its own domain, a known season silent");
    }
}
