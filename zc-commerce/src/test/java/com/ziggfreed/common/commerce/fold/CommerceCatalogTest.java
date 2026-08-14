package com.ziggfreed.common.commerce.fold;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonPrimitive;
import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;
import com.ziggfreed.common.board.BountyRef;
import com.ziggfreed.common.board.asset.BoardAsset;
import com.ziggfreed.common.board.asset.BoardAssetStore;
import com.ziggfreed.common.board.asset.BoardConfig;
import com.ziggfreed.common.board.asset.BountyAsset;
import com.ziggfreed.common.currency.CurrencyDef;
import com.ziggfreed.common.currency.asset.CurrencyAsset;
import com.ziggfreed.common.currency.asset.CurrencyConfig;
import com.ziggfreed.common.progress.asset.GeneratorCore;
import com.ziggfreed.common.shop.ShopOffer;
import com.ziggfreed.common.shop.asset.ShopAssetStore;
import com.ziggfreed.common.shop.asset.ShopEntryAsset;
import com.ziggfreed.common.shop.asset.ShopEntryGeneratorAsset;

/**
 * What a surface actually asks: the live content, read back off seeded stores.
 *
 * <p>The three catalogs answer differently ON PURPOSE and that difference is what most of this pins.
 * Wallets and boards are LIVE, so a reload lands on the next question; offers are a SNAPSHOT, because
 * a family written as one generator file has to be expanded before it exists at all - and expansion
 * needs the value sources a consumer registered.
 */
class CommerceCatalogTest {

    @BeforeEach
    @AfterEach
    void clearTheStores() {
        CurrencyConfig.getInstance().mergePackLayer(Map.of());
        CurrencyConfig.getInstance().mergeOwnerLayer(Map.of());
        ShopAssetStore.getInstance().mergeEntries(Map.of());
        ShopAssetStore.getInstance().mergeGenerators(Map.of());
        BoardConfig.getInstance().mergePackLayer(Map.of());
        BoardConfig.getInstance().mergeOwnerLayer(Map.of());
        BoardAssetStore.getInstance().merge(Map.of());
        CommerceCatalogs.installAxisValues(null);
        CommerceCatalogs.refreshShops();
    }

    // ==================== Wallets ====================

    @Nested
    class Wallets {

        @Test
        @DisplayName("a wallet a pack ships is spendable, and one nobody ships is simply not there")
        void thePackLayerIsWhatTheEngineReads() throws Exception {
            seedWallets();

            CurrencyDef token = CommerceCatalogs.currencies().get("bounty_token");

            assertNotNull(token);
            assertEquals("bounty_token", token.id());
            assertNull(CommerceCatalogs.currencies().get("no_such_wallet"),
                    "an unknown wallet is inert rather than invented");
        }

        @Test
        @DisplayName("a wallet id is matched however it is written")
        void idsResolveCaseInsensitively() throws Exception {
            seedWallets();

            assertNotNull(CommerceCatalogs.currencies().get("BOUNTY_TOKEN"));
            assertTrue(CommerceCatalogs.currencies().has("Bounty_Token"));
        }

        @Test
        @DisplayName("every wallet in circulation is listed, and a disabled one is not")
        void onlyEnabledWalletsAreListed() throws Exception {
            Map<String, CurrencyAsset> layer = new LinkedHashMap<>();
            layer.put("bounty_token", CommerceFoldFixtures.currency(
                    "Currencies/MMOSkillTree/Bounty_Token.json"));
            layer.put("retired", walletJson("retired", "{ \"Enabled\": false }"));
            CurrencyConfig.getInstance().mergePackLayer(layer);

            List<String> listed = new ArrayList<>();
            for (CurrencyDef def : CommerceCatalogs.currencies().all()) {
                listed.add(def.id());
            }

            assertTrue(listed.contains("bounty_token"));
            assertFalse(listed.contains("retired"), "a wallet taken out of circulation cannot be spent");
            assertNull(CommerceCatalogs.currencies().get("retired"));
        }

        @Test
        @DisplayName("a wallet is folded once and re-folded when the file behind it is replaced")
        void theFoldFollowsTheAssetRatherThanTheClock() throws Exception {
            seedWallets();
            CurrencyDef first = CommerceCatalogs.currencies().get("bounty_token");
            CurrencyDef again = CommerceCatalogs.currencies().get("bounty_token");
            assertSame(first, again, "a balance read must not rebuild a definition every time");

            seedWallets();
            assertNotNull(CommerceCatalogs.currencies().get("bounty_token"));
            assertFalse(first == CommerceCatalogs.currencies().get("bounty_token"),
                    "a reload replaces the file, so the definition behind it is folded again");
        }

        @Test
        @DisplayName("the owner layer outranks the pack, leaf by leaf")
        void anOwnerOverrideWins() throws Exception {
            seedWallets();
            CurrencyAsset packVersion = CurrencyConfig.getInstance().resolve("bounty_token");
            CurrencyConfig.getInstance().mergeOwnerLayer(Map.of("bounty_token",
                    CurrencyAsset.CODEC.decodeAndInheritJsonAsset(
                            RawJsonReader.fromJsonString("{ \"Cap\": 5000 }"), packVersion,
                            new AssetExtraInfo<>(new AssetExtraInfo.Data(CurrencyAsset.class,
                                    "bounty_token", "bounty_token")))));

            CurrencyDef token = CommerceCatalogs.currencies().get("bounty_token");

            assertNotNull(token);
            assertEquals(5000L, token.cap());
            assertEquals(packVersion.effectiveIconItemId(), token.iconItemId(),
                    "an override writes one leaf and keeps every other one");
        }
    }

    // ==================== Offers ====================

    @Nested
    class Offers {

        @Test
        @DisplayName("nothing is for sale until the offers have been read")
        void anUnreadCatalogueSellsNothing() {
            assertNull(CommerceCatalogs.shops().offer("shop_boost_mining"));
            assertTrue(CommerceCatalogs.shops().poolCandidates("featured").isEmpty());
        }

        @Test
        @DisplayName("an offer a pack ships is on sale after a refresh, by id however it is written")
        void seededOffersReachTheEngine() throws Exception {
            seedOffers();

            ShopOffer boost = CommerceCatalogs.shops().offer("boost_mining");

            assertNotNull(boost);
            assertEquals("boost_mining", boost.offerId());
            assertNotNull(CommerceCatalogs.shops().offer("BOOST_MINING"));
            assertFalse(boost.cost().isFree(), "the price came across with it");
        }

        @Test
        @DisplayName("a pooled offer is a candidate for its own shelf and for no other")
        void poolMembershipIsWhatTheDrawReads() throws Exception {
            seedOffers();

            List<String> featured = new ArrayList<>();
            for (ShopOffer offer : CommerceCatalogs.shops().poolCandidates("featured")) {
                featured.add(offer.offerId());
            }

            assertTrue(featured.contains("featured_cache_copper"));
            assertFalse(featured.contains("boost_mining"),
                    "a standing offer belongs to the page, not to a rotation");
            assertTrue(CommerceCatalogs.shops().poolCandidates("no_such_shelf").isEmpty());
        }

        @Test
        @DisplayName("a skeleton is never for sale")
        void anAbstractOfferIsNotAnOffer() throws Exception {
            seedOffers();

            assertNull(CommerceCatalogs.shops().offer("xp_packet"),
                    "a file that exists to be inherited from is not something a player can buy");
        }

        @Test
        @DisplayName("the storefront an offer names is what groups it")
        void offersAreReachableByStorefront() throws Exception {
            seedOffers();

            List<String> general = new ArrayList<>();
            for (ShopEntryOffer offer : CommerceCatalogs.shopContent().offersOf("General")) {
                general.add(offer.offerId());
            }

            assertTrue(general.contains("boost_mining"));
            assertTrue(general.contains("featured_cache_copper"));
        }

        @Test
        @DisplayName("a family written as one generator file is on sale once a value source answers")
        void oneRegisteredVocabularyWritesTheWholeFamily() throws Exception {
            seedOffers();
            seedGenerator();
            CommerceCatalogs.installAxisValues(() -> skills("mining", "fishing"));
            CommerceCatalogs.refreshShops();

            ShopOffer lesserMining = CommerceCatalogs.shops().offer("shop_xp_lesser_mining");

            assertNotNull(lesserMining, "the generator wrote a real offer, not a template");
            assertNotNull(CommerceCatalogs.shops().offer("shop_xp_master_fishing"));
            assertFalse(lesserMining.cost().isFree(),
                    "the row's own numbers were substituted into the price");
            assertEquals("lesser", lesserMining.poolTier(),
                    "and into the shelf slot it can fill");
        }

        @Test
        @DisplayName("with no value source installed the family is simply not written")
        void anUnansweredSourceWritesNothingRatherThanHalfAFamily() throws Exception {
            seedOffers();
            seedGenerator();
            CommerceCatalogs.refreshShops();

            assertNull(CommerceCatalogs.shops().offer("shop_xp_lesser_mining"));
            assertNotNull(CommerceCatalogs.shops().offer("boost_mining"),
                    "the hand-written offers are unaffected by a generator that could not run");
        }
    }

    // ==================== Boards ====================

    @Nested
    class Boards {

        @Test
        @DisplayName("a board a pack ships is openable, in the order it asked for")
        void seededBoardsReachTheEngine() throws Exception {
            seedBoards();

            BoardAssetSpec daily = CommerceCatalogs.boards().board("daily");

            assertNotNull(daily);
            assertEquals("daily", daily.boardId());
            assertNotNull(CommerceCatalogs.boards().board("DAILY"));
            assertEquals(List.of("bihourly", "daily"),
                    CommerceCatalogs.boards().boards().stream().map(BoardAssetSpec::boardId).sorted()
                            .toList());
        }

        @Test
        @DisplayName("the pool holds the contracts and never the skeletons they inherit from")
        void skeletonsAreNotPostable() throws Exception {
            seedContracts();

            List<String> pool = new ArrayList<>();
            for (BountyRef ref : CommerceCatalogs.boards().pool()) {
                pool.add(ref.bountyId());
            }

            assertTrue(pool.contains("bounty_hunt_trork"));
            assertFalse(pool.contains("bounty_kill"),
                    "a shared skeleton is a Parent target, never a posting");
            assertNull(CommerceCatalogs.boards().bounty("bounty_kill"));
            assertNotNull(CommerceCatalogs.boards().bounty("bounty_hunt_trork"));
        }

        @Test
        @DisplayName("a board and its pool are what the draw is handed, and they agree about membership")
        void theBoardAndThePoolLineUp() throws Exception {
            seedBoards();
            seedContracts();

            BoardAssetSpec daily = CommerceCatalogs.boards().board("daily");
            assertNotNull(daily);

            List<String> onDaily = new ArrayList<>();
            for (BountyRef ref : CommerceCatalogs.boards().pool()) {
                if (ref.isOn(daily.boardId())) {
                    onDaily.add(ref.bountyId());
                }
            }

            assertTrue(onDaily.contains("bounty_hunt_trork"));
        }
    }

    // ==================== Seeding ====================

    private static void seedWallets() throws IOException {
        CurrencyConfig.getInstance().mergePackLayer(Map.of(
                "bounty_token", CommerceFoldFixtures.currency(
                        "Currencies/MMOSkillTree/Bounty_Token.json"),
                "life_essence", CommerceFoldFixtures.currency(
                        "Currencies/MMOSkillTree/Life_Essence.json")));
    }

    private static void seedOffers() throws IOException {
        Map<String, ShopEntryAsset> layer = new LinkedHashMap<>();
        layer.put("boost_mining",
                CommerceFoldFixtures.entry("ShopEntries/MMOSkillTree/Boost_Mining.json"));
        layer.put("featured_cache_copper",
                CommerceFoldFixtures.entry("ShopEntries/MMOSkillTree/Featured_Cache_Copper.json"));
        layer.put("xp_packet", CommerceFoldFixtures.entry("ShopEntries/MMOSkillTree/Xp_Packet.json"));
        ShopAssetStore.getInstance().mergeEntries(layer);
        CommerceCatalogs.refreshShops();
    }

    private static void seedGenerator() throws IOException {
        String body = CommerceFoldFixtures.read("ShopEntryGenerators/MMOSkillTree/Xp_Packets.json");
        ShopEntryGeneratorAsset generator = ShopEntryGeneratorAsset.CODEC.decodeAndInheritJsonAsset(
                RawJsonReader.fromJsonString(body), null,
                new AssetExtraInfo<>(new AssetExtraInfo.Data(ShopEntryGeneratorAsset.class,
                        "xp_packets", null)));
        ShopAssetStore.getInstance().mergeGenerators(Map.of("xp_packets", generator));
    }

    private static void seedBoards() throws IOException {
        BoardConfig.getInstance().mergePackLayer(Map.of(
                "daily", CommerceFoldFixtures.board("Boards/MMOSkillTree/Daily.json"),
                "bihourly", CommerceFoldFixtures.board("Boards/MMOSkillTree/Bihourly.json")));
    }

    private static void seedContracts() throws IOException {
        BountyAsset base = CommerceFoldFixtures.bounty("Bounties/MMOSkillTree/Bounty_Kill.json",
                null, null);
        Map<String, BountyAsset> layer = new LinkedHashMap<>();
        layer.put("bounty_kill", base);
        layer.put("bounty_hunt_trork", CommerceFoldFixtures.bounty(
                "Bounties/MMOSkillTree/Bounty_Hunt_Trork.json", base, "bounty_kill"));
        BoardAssetStore.getInstance().merge(layer);
    }

    private static CurrencyAsset walletJson(String id, String json) throws IOException {
        return CurrencyAsset.CODEC.decodeAndInheritJsonAsset(RawJsonReader.fromJsonString(json), null,
                new AssetExtraInfo<>(new AssetExtraInfo.Data(CurrencyAsset.class, id, null)));
    }

    /** A value source answering the one vocabulary the shipped generator's skill axis names. */
    private static GeneratorCore.AxisValueSource skills(String... ids) {
        return new GeneratorCore.AxisValueSource() {

            @Override
            public boolean isRegistered(String sourceId) {
                return sourceId != null && "mmoskilltree:skills".equalsIgnoreCase(sourceId);
            }

            @Override
            public List<Map<String, JsonPrimitive>> rows(String sourceId, String token,
                    Map<String, String> filter) {
                List<Map<String, JsonPrimitive>> out = new ArrayList<>();
                for (String id : ids) {
                    Map<String, JsonPrimitive> row = new LinkedHashMap<>();
                    row.put(token == null ? "value" : token,
                            new JsonPrimitive(id.toLowerCase(Locale.ROOT)));
                    out.add(row);
                }
                return out;
            }
        };
    }
}
