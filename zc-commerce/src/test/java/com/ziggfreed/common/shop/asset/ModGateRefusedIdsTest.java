package com.ziggfreed.common.shop.asset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nonnull;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.ziggfreed.common.asset.AbstractKeyedAssetConfig;
import com.ziggfreed.common.asset.ModGateFold;
import com.ziggfreed.common.board.asset.BoardAsset;
import com.ziggfreed.common.board.asset.BoardConfig;
import com.ziggfreed.common.board.asset.BoardValidator;
import com.ziggfreed.common.board.asset.BountyAsset;
import com.ziggfreed.common.validation.Finding;

/**
 * No audit line names an id the mod gate refused (a file gated on a mod this server lacks, or an owner entry
 * that took its whole id out): an {@code Includes}, an offer's or a shelf's {@code Shop}, a contract's board
 * and a price's or a header's wallet naming one say nothing, because that id is absent on purpose and naming
 * it would put the missing mod's content into the log of the very server that lacks it. An id nothing
 * defines and nothing refused still warns. The refusals are seeded on the stores themselves, as each store's
 * load handler leaves them, and cleared after every test.
 */
class ModGateRefusedIdsTest {

    private static final String MMO = "Ziggfreed:MMOSkillTree";

    /** One wallet exists; Test_Gated_Coin was refused by the mod gate; anything else is unknown. */
    private static final ShopValidator.CurrencyProbe WALLETS = new ShopValidator.CurrencyProbe() {
        @Override
        public boolean defines(@Nonnull String currencyId) {
            return "bounty_token".equals(currencyId);
        }

        @Override
        public boolean refused(@Nonnull String currencyId) {
            return "test_gated_coin".equals(currencyId);
        }
    };

    private static final String PIE = "\"Rewards\": [ { \"Kind\": \"Item\", \"Params\": { \"Item\": \"Ore_Iron\" } } ]";
    private static final String STEP = "\"Objectives\": { \"main\": { \"Kind\": \"KILL_ENTITY\", \"Amount\": 1 } }";
    private static final String PAY = "\"Rewards\": { \"Claim\": [ { \"Kind\": \"Item\","
            + " \"Params\": { \"Item\": \"Ore_Iron\" } } ] }";

    @AfterEach
    void forgetTheRefusals() {
        forget(ShopConfig.getInstance());
        forget(BoardConfig.getInstance());
    }

    /** An empty pack and owner layer, which refuse nothing. */
    private static <T> void forget(AbstractKeyedAssetConfig<T> config) {
        config.mergePackLayer(Map.of());
        config.mergeOwnerLayer(Map.of());
    }

    /** The pack fold refused {@code id} on the missing mod, as the store's load handler leaves it. */
    private static <T> void packRefused(AbstractKeyedAssetConfig<T> config, String id) {
        config.mergePackLayer(new ModGateFold<T>(config.modGateStore(), Map.of(), Map.of(id, MMO)));
    }

    /** The owner's own gate took {@code id} out whole (M295), as the owner reader leaves it. */
    private static <T> void ownerTookOut(AbstractKeyedAssetConfig<T> config, String id) {
        config.mergeOwnerLayer(Map.of(), Map.of(id, MMO));
    }

    private static <T> Map<String, T> one(String id, T value) {
        Map<String, T> map = new LinkedHashMap<>();
        map.put(id, value);
        return map;
    }

    private static List<Finding> coded(List<Finding> findings, String code) {
        return findings.stream().filter(f -> code.equals(f.code())).toList();
    }

    private static void namesNoRefusedId(List<Finding> findings) {
        for (Finding finding : findings) {
            assertFalse(finding.message().toLowerCase(Locale.ROOT).contains("test_gated"),
                    "no line names an id the mod gate refused: " + finding);
        }
    }

    @Test
    void anIncludeNamingARefusedStorefrontSaysNothingWhileAnUnknownOneStillWarns() throws Exception {
        packRefused(ShopConfig.getInstance(), "Test_Gated_Stall");
        ownerTookOut(ShopConfig.getInstance(), "Test_Gated_Owner_Stall");

        List<Finding> findings = ShopValidator.validate(Map.of(), one("host", CommerceValidatorTest.shop(
                "{ \"Currencies\": [\"bounty_token\"],"
                        + " \"Includes\": [\"Test_Gated_Stall\", \"Test_Gated_Owner_Stall\", \"Nowhere\"] }",
                "Host")), Map.of(), WALLETS, null, null, null);

        List<Finding> unknown = coded(findings, "UNKNOWN_INCLUDE");
        assertEquals(1, unknown.size(), "only the id nothing refused: " + unknown);
        assertTrue(unknown.get(0).message().contains("nowhere"), unknown.get(0).message());
        namesNoRefusedId(findings);
    }

    @Test
    void anOffersOrAShelfsShopNamingARefusedStorefrontSaysNothing() throws Exception {
        packRefused(ShopConfig.getInstance(), "Test_Gated_Stall");
        ownerTookOut(ShopConfig.getInstance(), "Test_Gated_Owner_Stall");
        Map<String, ShopEntryAsset> offers = new LinkedHashMap<>();
        offers.put("pie", CommerceValidatorTest.entry("{ \"Shop\": \"Test_Gated_Stall\", " + PIE + " }", "Pie"));
        offers.put("tart", CommerceValidatorTest.entry("{ \"Shop\": \"Test_Gated_Owner_Stall\", " + PIE + " }",
                "Tart"));
        offers.put("typo", CommerceValidatorTest.entry("{ \"Shop\": \"Nowhere\", " + PIE + " }", "Typo"));
        Map<String, ShopPoolAsset> shelves = new LinkedHashMap<>();
        shelves.put("gated_shelf", CommerceValidatorTest.pool("{ \"Shop\": \"Test_Gated_Stall\" }", "Gated_Shelf"));
        shelves.put("owner_shelf", CommerceValidatorTest.pool("{ \"Shop\": \"Test_Gated_Owner_Stall\" }",
                "Owner_Shelf"));

        List<Finding> findings = ShopValidator.validate(offers, Map.of(), shelves, WALLETS, null, null, null);

        List<Finding> unknown = coded(findings, "UNKNOWN_SHOP");
        assertEquals(List.of("typo"), unknown.stream().map(Finding::sourceId).toList(),
                "only the offer naming a storefront nothing refused: " + unknown);
        namesNoRefusedId(findings);
    }

    @Test
    void aContractNamingARefusedBoardSaysNothingWhileAnUnknownOneStillWarns() throws Exception {
        packRefused(BoardConfig.getInstance(), "Test_Gated_Board");
        ownerTookOut(BoardConfig.getInstance(), "Test_Gated_Owner_Board");

        List<Finding> findings = BoardValidator.validate(Map.of(), one("job", CommerceValidatorTest.bounty(
                "{ \"Boards\": [ { \"Board\": \"Test_Gated_Board\" }, { \"Board\": \"Test_Gated_Owner_Board\" },"
                        + " { \"Board\": \"Nowhere\" } ], " + STEP + ", " + PAY + " }", "Job")),
                WALLETS, null, null, null, null);

        List<Finding> unknown = coded(findings, "UNKNOWN_BOARD");
        assertEquals(1, unknown.size(), "only the board nothing refused: " + unknown);
        assertTrue(unknown.get(0).message().contains("nowhere"), unknown.get(0).message());
        namesNoRefusedId(findings);
    }

    @Test
    void aPriceOrAHeaderInARefusedWalletSaysNothingWhileAnUnknownOneStillWarns() throws Exception {
        List<Finding> shop = ShopValidator.validate(
                one("pie", CommerceValidatorTest.entry("{ \"Shop\": \"General\","
                        + " \"Cost\": { \"Currencies\": { \"Test_Gated_Coin\": 5, \"Dragon_Scale\": 5 } }, "
                        + PIE + " }", "Pie")),
                one("general", CommerceValidatorTest.shop(
                        "{ \"Currencies\": [\"bounty_token\", \"Test_Gated_Coin\"] }", "General")),
                one("featured", CommerceValidatorTest.pool("{ \"Shop\": \"General\", \"Reroll\": { \"Cost\":"
                        + " { \"Currencies\": { \"Test_Gated_Coin\": 1 } }, \"MaxPerPeriod\": 3 } }", "Featured")),
                WALLETS, null, null, null);
        List<Finding> board = BoardValidator.validate(
                one("daily", CommerceValidatorTest.board("{ \"Currencies\": [\"Test_Gated_Coin\", \"Moon_Shell\"],"
                        + " \"Reroll\": { \"Cost\": { \"Currencies\": { \"Test_Gated_Coin\": 5 } },"
                        + " \"MaxPerPeriod\": 3 } }", "Daily")),
                Map.of(), WALLETS, null, null, null, null);

        List<Finding> unknownInShop = coded(shop, "UNKNOWN_CURRENCY");
        assertEquals(1, unknownInShop.size(), "only the wallet nothing refused: " + unknownInShop);
        assertTrue(unknownInShop.get(0).message().contains("Dragon_Scale"), unknownInShop.get(0).message());
        List<Finding> unknownOnBoard = coded(board, "UNKNOWN_CURRENCY");
        assertEquals(1, unknownOnBoard.size(), "only the header wallet nothing refused: " + unknownOnBoard);
        assertTrue(unknownOnBoard.get(0).message().contains("moon_shell"), unknownOnBoard.get(0).message());
        assertTrue(coded(board, "MISSING_REROLL_CURRENCY").isEmpty(), "a reroll priced in it says nothing either");
        namesNoRefusedId(shop);
        namesNoRefusedId(board);
    }

    @Test
    void anIdNothingRefusedStillWarnsWhateverTheStoresRefused() throws Exception {
        packRefused(ShopConfig.getInstance(), "Test_Gated_Stall");
        packRefused(BoardConfig.getInstance(), "Test_Gated_Board");

        Set<String> shopCodes = Set.copyOf(ShopValidator.validate(
                one("typo", CommerceValidatorTest.entry("{ \"Shop\": \"Test_Gated_Stal\", " + PIE + " }", "Typo")),
                Map.of(), Map.of(), WALLETS, null, null, null).stream().map(Finding::code).toList());
        Map<String, BountyAsset> contracts = one("job", CommerceValidatorTest.bounty(
                "{ \"Boards\": [ { \"Board\": \"Test_Gated_Bord\" } ], " + STEP + ", " + PAY + " }", "Job"));
        Set<String> boardCodes = Set.copyOf(BoardValidator.validate(Map.<String, BoardAsset>of(), contracts,
                WALLETS, null, null, null, null).stream().map(Finding::code).toList());

        assertTrue(shopCodes.contains("UNKNOWN_SHOP"), "a misspelt storefront is still a finding: " + shopCodes);
        assertTrue(boardCodes.contains("UNKNOWN_BOARD"), "a misspelt board is still a finding: " + boardCodes);
    }
}
