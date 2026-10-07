package com.ziggfreed.common.commerce.fold;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.annotation.Nonnull;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;
import com.ziggfreed.common.commerce.InMemoryCommerceStore;
import com.ziggfreed.common.commerce.page.CommerceLabels;
import com.ziggfreed.common.commerce.page.CommercePages;
import com.ziggfreed.common.cost.CostEngine;
import com.ziggfreed.common.currency.CurrencyCatalog;
import com.ziggfreed.common.currency.CurrencyDef;
import com.ziggfreed.common.currency.CurrencyEngine;
import com.ziggfreed.common.currency.ItemWallet;
import com.ziggfreed.common.factor.FeatureFlags;
import com.ziggfreed.common.loot.reward.RewardHandler;
import com.ziggfreed.common.loot.reward.RewardKindRegistry;
import com.ziggfreed.common.loot.reward.RewardSpec;
import com.ziggfreed.common.progress.gate.GateEvaluator;
import com.ziggfreed.common.season.SeasonGate;
import com.ziggfreed.common.shop.ShopCatalog;
import com.ziggfreed.common.shop.ShopEngine;
import com.ziggfreed.common.shop.ShopOffer;
import com.ziggfreed.common.shop.asset.ShopAssetStore;
import com.ziggfreed.common.shop.asset.ShopConfig;
import com.ziggfreed.common.shop.asset.ShopEntryAsset;
import com.ziggfreed.common.shop.asset.ShopPoolAsset;
import com.ziggfreed.common.shop.asset.ShopPoolConfig;
import com.ziggfreed.common.shop.asset.ShopValidator;
import com.ziggfreed.common.shop.asset.StorefrontAsset;
import com.ziggfreed.common.subject.Subject;
import com.ziggfreed.common.ui.route.Destination;
import com.ziggfreed.common.ui.route.Destinations;
import com.ziggfreed.common.util.PeriodMath;
import com.ziggfreed.common.validation.Finding;
import com.ziggfreed.common.validation.Severity;
import com.ziggfreed.common.world.WhereValidator.LoadedWorld;

/**
 * A storefront's Includes, over a shared stall that ships switched off: two hosts list the stall's offers
 * and draw its shelf after their own, each offer judged by the host it is listed at (one daily count
 * wherever it is bought, its own Season and gates); the stall itself never appears on its own and the audit
 * says nothing of it; the host's wallets and category names come from the stall; a loop is cut where it
 * comes back round; the Shop destination opens an all-year storefront that includes the stall, anywhere.
 * The ids are unique to this class.
 */
class StorefrontIncludesTest {

    private static final Subject BUYER = Subject.of(UUID.randomUUID(), "Buyer");
    private static final long DAY_ONE = 100L * PeriodMath.DAY_MS + 3600_000L;
    private static final String KIND = "Test_Includes_Payout";
    private static final String WINTER = "Test_Includes_Winter";
    private static final LoadedWorld OVERWORLD = new LoadedWorld("default", "Default");

    private AtomicBoolean winter;

    @BeforeEach
    void seed() throws IOException {
        winter = new AtomicBoolean(false);
        FeatureFlags.register(SeasonGate.NAMESPACE, SeasonGate.featureOf(WINTER), "test", winter::get);
        clearStores();
        Map<String, StorefrontAsset> shops = new LinkedHashMap<>();
        shops.put("test_harvest_host", storefront("test_harvest_host", """
                { "Order": 10, "Currencies": [ "Harvest_Coin" ], "CategoryOrder": [ "Feast" ],
                  "Includes": [ "Test_Festival_Stall" ] }
                """));
        shops.put("test_winter_host", storefront("test_winter_host", """
                { "Order": 20, "Currencies": [ "Winter_Coin" ], "Includes": [ "Test_Festival_Stall" ] }
                """));
        shops.put("test_festival_stall", storefront("test_festival_stall", """
                { "Enabled": false, "Order": -100, "Requires": { "Permission": "test.stall.only" },
                  "Currencies": [ "Festival_Token", "Harvest_Coin" ], "CategoryOrder": [ "Tokens", "Feast" ],
                  "Categories": { "Tokens": { "TitleKey": "shop.category.test_tokens" } } }
                """));
        shops.put("test_all_year", storefront("test_all_year", """
                { "Order": 30, "Includes": [ "Test_Festival_Stall" ] }
                """));
        ShopConfig.getInstance().mergePackLayer(shops);
        ShopPoolConfig.getInstance().mergePackLayer(Map.of("test_festival_shelf",
                pool("test_festival_shelf", "{ \"Shop\": \"Test_Festival_Stall\" }")));
        Map<String, ShopEntryAsset> offers = new LinkedHashMap<>();
        offers.put("test_pie", offer("test_pie", """
                { "Shop": "Test_Harvest_Host", "Listing": { "Category": "Feast" },
                  "Rewards": [ { "Kind": "Test_Includes_Payout", "Params": { "what": "pie" } } ] }
                """));
        offers.put("test_token_pack", offer("test_token_pack", """
                { "Shop": "Test_Festival_Stall", "Listing": { "Category": "Tokens" }, "Limits": { "Daily": 1 },
                  "Rewards": [ { "Kind": "Test_Includes_Payout", "Params": { "what": "token" } } ] }
                """));
        offers.put("test_winter_wreath", offer("test_winter_wreath", """
                { "Shop": "Test_Festival_Stall", "Season": "Test_Includes_Winter",
                  "Rewards": [ { "Kind": "Test_Includes_Payout", "Params": { "what": "wreath" } } ] }
                """));
        offers.put("test_festival_lantern", offer("test_festival_lantern", """
                { "Shop": "Test_Festival_Stall", "Pool": { "Id": "Test_Festival_Shelf" },
                  "Rewards": [ { "Kind": "Test_Includes_Payout", "Params": { "what": "lantern" } } ] }
                """));
        ShopAssetStore.getInstance().mergeEntries(offers);
        CommerceCatalogs.refreshShops();
    }

    @AfterEach
    void forget() {
        clearStores();
        FeatureFlags.reset();
        Destinations.clearForTests();
    }

    @Test
    @DisplayName("a switched-off stall included by two hosts shows its offers at both, one daily limit")
    void aSwitchedOffStallSuppliesTwoHostsWithOneDailyLimit() {
        assertFalse(CommerceCatalogs.shops().offer("test_token_pack").enabled(),
                "judged by its own switched-off stall, the offer would be gone everywhere");
        List<String> atHarvest = ids(StorefrontView.offers("Test_Harvest_Host"));
        assertEquals("test_pie", atHarvest.get(0), "the host's own offers first");
        assertEquals(sorted("test_pie", "test_token_pack", "test_festival_lantern"), sorted(atHarvest),
                "then the stall's, the wreath's season being off");
        assertTrue(ids(StorefrontView.offers("test_winter_host")).contains("test_token_pack"));

        InMemoryCommerceStore store = new InMemoryCommerceStore();
        List<String> granted = new ArrayList<>();
        ShopEngine engine = engine(store, granted, CommerceCatalogs.shops());
        ShopOffer harvestToken = StorefrontView.offer("test_harvest_host", "test_token_pack");
        ShopOffer winterToken = StorefrontView.offer("test_winter_host", "test_token_pack");
        assertTrue(harvestToken.enabled(), "judged by the host it is listed at, which is open");
        assertNull(harvestToken.storefrontRequires(), "the stall's own lock is not asked at a host; the host's is");
        assertEquals(harvestToken.offerId(), winterToken.offerId());

        assertTrue(engine.purchase(BUYER, harvestToken, DAY_ONE).ok());
        ShopEngine.PurchaseOutcome again = engine.purchase(BUYER, winterToken, DAY_ONE);

        assertFalse(again.ok(), "the count is filed under the offer id, so it is the same at every host");
        assertEquals(ShopEngine.REASON_LIMIT_DAILY, again.reason());
        assertEquals(1, store.purchasesToday(BUYER, "test_token_pack", ShopEngine.epochDay(DAY_ONE)));
        assertEquals(List.of("token"), granted);
    }

    @Test
    @DisplayName("an included offer's presence follows the host it is listed at")
    void anIncludedOffersPresenceFollowsItsHost() throws IOException {
        ShopConfig.getInstance().mergeOwnerLayer(Map.of("test_winter_host", storefront("test_winter_host",
                "{ \"Enabled\": false, \"Includes\": [ \"Test_Festival_Stall\" ] }")));

        assertFalse(StorefrontView.offer("test_winter_host", "test_token_pack").enabled(),
                "a press on the page of a host switched off since it was drawn refuses");
        assertTrue(StorefrontView.offer("test_harvest_host", "test_token_pack").enabled(),
                "while the same offer at an open host still sells");
        assertSame(CommerceCatalogs.shops().offer("test_pie"), StorefrontView.offer("test_harvest_host", "test_pie"),
                "a host's own offer is the catalogue's own view");
    }

    @Test
    @DisplayName("the switched-off stall's rotating shelf is drawn at a host, judged by the host")
    void theStallsShelfIsDrawnAtAHost() {
        List<ShelfSpec> shelves = StorefrontView.shelves("test_harvest_host");
        assertEquals(List.of("test_festival_shelf"),
                shelves.stream().map(shelf -> shelf.shelfId().toLowerCase(Locale.ROOT)).toList());
        ShopEngine shared = engine(new InMemoryCommerceStore(), new ArrayList<>(), CommerceCatalogs.shops());
        ShopEngine atHarvest = engine(new InMemoryCommerceStore(), new ArrayList<>(),
                StorefrontView.catalogAt("test_harvest_host"));

        assertEquals(List.of(), offerIds(shared.shelfCandidates(shelves.get(0))),
                "on its own, the switched-off stall's shelf has nothing to draw");
        assertEquals(List.of("test_festival_lantern"), offerIds(atHarvest.shelfCandidates(shelves.get(0))),
                "at a host, its offers are judged by the host");
    }

    @Test
    @DisplayName("the switched-off stall never appears on its own, and the audit says nothing of it")
    void theSwitchedOffStallNeverAppearsOnItsOwn() {
        StorefrontAsset stall = ShopConfig.getInstance().resolve("test_festival_stall");
        assertNotNull(stall);
        assertFalse(shopIds(ShopConfig.getInstance().listed()).contains("test_festival_stall"),
                "in no server-wide list");
        assertFalse(shopIds(ShopConfig.getInstance().listedIn(OVERWORLD)).contains("test_festival_stall"),
                "nor in a player's list, which is what a hub tile reads");
        assertEquals("test_harvest_host", CommercePages.firstShopId(OVERWORLD),
                "never the unnamed default, though it would sort first by Order");
        assertEquals("test_harvest_host", CommercePages.firstShopId());
        assertFalse(stall.isAvailableIn(OVERWORLD), "named by a destination, its own page opens closed");
        assertEquals(List.of(), ids(CommerceCatalogs.shopContent().availableOffersOf("test_festival_stall")),
                "and sells nothing there");

        List<Finding> findings = ShopValidator.validate(ShopAssetStore.getInstance().resolveAll(null),
                ShopConfig.getInstance().all(), ShopPoolConfig.getInstance().all(), null, null, null, null);
        assertEquals(List.of(), findings.stream().filter(f -> f.severity() != Severity.INFO).toList(),
                "a switched-off stall that hosts include is no orphan: no unknown storefront, no unknown include");
    }

    @Test
    @DisplayName("the host's wallet strip and category names come from the stall it includes")
    void theHostsWalletsAndCategoryNamesComeFromTheStall() {
        assertEquals(List.of("harvest_coin", "festival_token"), StorefrontView.currencyIds("test_harvest_host"),
                "the host's own wallets, then the stall's, each once");
        assertEquals(List.of("feast", "tokens"), StorefrontView.categoryOrder("test_harvest_host"),
                "the host's CategoryOrder first, then the stall's");
        assertEquals("test_festival_stall", StorefrontView.namingCategory("test_harvest_host", "tokens").getId());
        assertEquals("shop.category.test_tokens", CommerceLabels.category(
                        StorefrontView.namingCategory("test_harvest_host", "tokens"), "tokens", null).getMessageId(),
                "the page's own label call reads the stall's word for the category");
        assertEquals("test_harvest_host", StorefrontView.namingCategory("test_harvest_host", "feast").getId(),
                "a category nobody named falls back to the host");
    }

    @Test
    @DisplayName("a season-gated offer in an included stall hides at every host while its season is off")
    void aSeasonGatedOfferInAnIncludedStallHidesWhileItsSeasonIsOff() {
        assertFalse(ids(StorefrontView.offers("test_harvest_host")).contains("test_winter_wreath"));
        assertFalse(ids(StorefrontView.offers("test_winter_host")).contains("test_winter_wreath"),
                "a host does not lift the offer's own Season");
        assertFalse(StorefrontView.offer("test_harvest_host", "test_winter_wreath").enabled(),
                "and a stale press on it refuses");

        winter.set(true);

        assertTrue(ids(StorefrontView.offers("test_harvest_host")).contains("test_winter_wreath"));
        assertTrue(ids(StorefrontView.offers("test_winter_host")).contains("test_winter_wreath"));
    }

    @Test
    @DisplayName("a loop of Includes is cut where it comes back round, each storefront listed once")
    void aLoopIsCutWhereItComesBackRound() throws IOException {
        Map<String, StorefrontAsset> shops = new LinkedHashMap<>();
        shops.put("test_loop_a", storefront("test_loop_a", "{ \"Includes\": [ \"Test_Loop_B\" ] }"));
        shops.put("test_loop_b", storefront("test_loop_b",
                "{ \"Includes\": [ \"Test_Loop_A\", \"Test_Festival_Stall\", \"Test_Nowhere\" ] }"));
        shops.put("test_festival_stall", storefront("test_festival_stall", "{ \"Enabled\": false }"));
        ShopConfig.getInstance().mergePackLayer(shops);

        assertEquals(List.of("test_loop_a", "test_loop_b", "test_festival_stall"),
                StorefrontView.chain("test_loop_a"), "an include nothing defines adds nothing");
        assertEquals(sorted("test_festival_lantern", "test_token_pack"),
                sorted(ids(StorefrontView.offers("test_loop_a"))),
                "the stall's offers once each, however many routes reach it");
    }

    @Test
    @DisplayName("the Shop destination opens an all-year storefront that includes the stall, in any world")
    void theShopDestinationOpensTheAllYearStall() throws Exception {
        Destinations.clearForTests();
        CommerceDestinations.register();
        CommerceDestinations.Shop open = assertInstanceOf(CommerceDestinations.Shop.class,
                Destination.CODEC.decodeJson(RawJsonReader.fromJsonString(
                        "{ \"Type\": \"Shop\", \"Shop\": \"Test_All_Year\" }"), new ExtraInfo()));

        assertEquals("Test_All_Year", open.getShop());
        assertTrue(Destinations.validate(open, "an_almanac_link").isEmpty(), "the storefront exists");
        StorefrontAsset allYear = ShopConfig.getInstance().resolve(open.getShop());
        assertNotNull(allYear);
        assertTrue(allYear.isAvailableIn(OVERWORLD), "with no Where it exists in every world; no NPC is needed");
        assertEquals(sorted("test_festival_lantern", "test_token_pack"),
                sorted(ids(StorefrontView.offers(open.getShop()))),
                "the page the destination opens lists the stall's offers");
    }

    // ==================== helpers ====================

    private static void clearStores() {
        ShopAssetStore.getInstance().mergeEntries(Map.of());
        ShopAssetStore.getInstance().mergeGenerators(Map.of());
        ShopConfig.getInstance().mergePackLayer(Map.of());
        ShopConfig.getInstance().mergeOwnerLayer(Map.of());
        ShopPoolConfig.getInstance().mergePackLayer(Map.of());
        CommerceCatalogs.installAxisValues(null);
        CommerceCatalogs.refreshShops();
    }

    private static ShopEntryAsset offer(String id, String json) throws IOException {
        return ShopEntryAsset.CODEC.decodeAndInheritJsonAsset(RawJsonReader.fromJsonString(json), null,
                new AssetExtraInfo<>(new AssetExtraInfo.Data(ShopEntryAsset.class, id, null)));
    }

    private static StorefrontAsset storefront(String id, String json) throws IOException {
        return StorefrontAsset.CODEC.decodeAndInheritJsonAsset(RawJsonReader.fromJsonString(json), null,
                new AssetExtraInfo<>(new AssetExtraInfo.Data(StorefrontAsset.class, id, null)));
    }

    private static ShopPoolAsset pool(String id, String json) throws IOException {
        return ShopPoolAsset.CODEC.decodeAndInheritJsonAsset(RawJsonReader.fromJsonString(json), null,
                new AssetExtraInfo<>(new AssetExtraInfo.Data(ShopPoolAsset.class, id, null)));
    }

    private static List<String> ids(List<ShopEntryOffer> offers) {
        return offers.stream().map(ShopEntryOffer::offerId).toList();
    }

    private static List<String> offerIds(List<ShopOffer> offers) {
        return offers.stream().map(ShopOffer::offerId).toList();
    }

    private static List<String> shopIds(List<StorefrontAsset> shops) {
        return shops.stream().map(StorefrontAsset::getId).toList();
    }

    private static List<String> sorted(String... ids) {
        return sorted(List.of(ids));
    }

    private static List<String> sorted(List<String> ids) {
        return ids.stream().sorted().toList();
    }

    private static ShopEngine engine(InMemoryCommerceStore store, List<String> granted, ShopCatalog catalog) {
        CurrencyEngine currencies = CurrencyEngine.builder()
                .catalog(CurrencyCatalog.of(List.of(CurrencyDef.builder("Festival_Token").build())))
                .items(ItemWallet.NONE).store(store).warn(msg -> { }).build();
        CostEngine costs = CostEngine.builder(currencies).items(ItemWallet.NONE).warn(msg -> { }).build();
        RewardKindRegistry kinds = new RewardKindRegistry();
        kinds.register(KIND, "test", new RewardHandler() {
            @Override
            public void grant(@Nonnull RewardSpec spec, @Nonnull Subject subject) {
                granted.add(spec.paramOr("what", "?"));
            }
        });
        return ShopEngine.builder(costs, GateEvaluator.builder().warn(msg -> { }).build())
                .catalog(catalog).store(store).kinds(kinds).warn(msg -> { }).info(msg -> { }).build();
    }
}
