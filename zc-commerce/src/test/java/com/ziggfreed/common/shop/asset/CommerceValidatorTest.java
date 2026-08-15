package com.ziggfreed.common.shop.asset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;
import com.ziggfreed.common.board.asset.BoardAsset;
import com.ziggfreed.common.board.asset.BountyAsset;
import com.ziggfreed.common.board.asset.BoardValidator;
import com.ziggfreed.common.currency.asset.CurrencyAsset;
import com.ziggfreed.common.currency.asset.CurrencyValidator;
import com.ziggfreed.common.validation.Finding;
import com.ziggfreed.common.validation.Severity;

/**
 * What the commerce audits SAY, and above all which silences they break: a shelf nothing can fill, a
 * price in a wallet nobody has, a contract no board posts. Each of these ships as content that
 * quietly does not work, so the finding is the whole point.
 *
 * <p>The severity split is asserted alongside the codes, because it is a contract of its own: an
 * unknown id is a WARNING (its owner may be a mod this server does not run), while something
 * impossible whatever anybody installs is an ERROR.
 */
class CommerceValidatorTest {

    /** Only these two wallets exist, so anything else in a price is unknown. */
    private static final ShopValidator.CurrencyProbe WALLETS =
            id -> Set.of("bounty_token", "life_essence").contains(id);

    static ShopEntryAsset entry(String json, String id) throws IOException {
        return CommerceFixtureSupport.entry(json, id, null, null);
    }

    static ShopPoolAsset pool(String json, String id) throws IOException {
        return CommerceFixtureSupport.pool(json, id);
    }

    static StorefrontAsset shop(String json, String id) throws IOException {
        return CommerceFixtureSupport.shop(json, id);
    }

    static BoardAsset board(String json, String id) throws IOException {
        AssetExtraInfo.Data data = new AssetExtraInfo.Data(BoardAsset.class, id, null);
        return BoardAsset.CODEC.decodeAndInheritJsonAsset(
                RawJsonReader.fromJsonString(json), null, new AssetExtraInfo<>(data));
    }

    static BountyAsset bounty(String json, String id) throws IOException {
        AssetExtraInfo.Data data = new AssetExtraInfo.Data(BountyAsset.class, id, null);
        return BountyAsset.CODEC.decodeAndInheritJsonAsset(
                RawJsonReader.fromJsonString(json), null, new AssetExtraInfo<>(data));
    }

    private static <T> Map<String, T> one(String id, T value) {
        Map<String, T> map = new LinkedHashMap<>();
        map.put(id, value);
        return map;
    }

    private static Finding find(List<Finding> findings, String code) {
        return findings.stream().filter(f -> code.equals(f.code())).findFirst().orElse(null);
    }

    private static boolean has(List<Finding> findings, String code) {
        return find(findings, code) != null;
    }

    // ==================== shop ====================

    @Nested
    class Shops {

        @Test
        void aShelfNoOfferNamesIsAnErrorRatherThanAnEmptyPage() throws Exception {
            List<Finding> findings = ShopValidator.validate(Map.of(),
                    one("general", shop("{}", "General")),
                    one("featured", pool("{ \"Shop\": \"General\" }", "Featured")),
                    WALLETS, null, null, null);

            assertEquals(Severity.ERROR, find(findings, "EMPTY_POOL").severity());
        }

        @Test
        void aSlotNoOfferCanFillIsAnError() throws Exception {
            Map<String, ShopEntryAsset> entries = one("packet", entry("""
                    { "Shop": "XpExchange", "Pool": { "Id": "XpExchange", "Tier": "lesser" },
                      "Cost": { "Currencies": { "bounty_token": 75 } },
                      "Rewards": [ { "Kind": "Mmo_Xp", "Params": { "Amount": "1" } } ] }
                    """, "packet"));

            List<Finding> findings = ShopValidator.validate(entries,
                    one("xpexchange", shop("{}", "XpExchange")),
                    one("xpexchange", pool("""
                            { "Shop": "XpExchange",
                              "Slots": [ { "Tier": "lesser" }, { "Tier": "master" } ] }
                            """, "XpExchange")),
                    WALLETS, null, null, null);

            assertEquals(Severity.ERROR, find(findings, "UNFILLABLE_SLOT").severity());
        }

        @Test
        void aSlotWantingMoreDistinctOffersThanExistIsAWarning() throws Exception {
            Map<String, ShopEntryAsset> entries = one("packet", entry("""
                    { "Shop": "XpExchange", "Pool": { "Id": "XpExchange", "Tier": "lesser" },
                      "Rewards": [ { "Kind": "Mmo_Xp", "Params": { "Amount": "1" } } ] }
                    """, "packet"));

            List<Finding> findings = ShopValidator.validate(entries,
                    one("xpexchange", shop("{}", "XpExchange")),
                    one("xpexchange", pool("""
                            { "Shop": "XpExchange", "Slots": [ { "Tier": "lesser", "Count": 3 } ] }
                            """, "XpExchange")),
                    WALLETS, null, null, null);

            assertEquals(Severity.WARNING, find(findings, "OVERSUBSCRIBED_POOL").severity());
        }

        @Test
        void aPriceInAWalletNobodyDefinesIsAWarningBecauseItsPackMayArriveLater() throws Exception {
            List<Finding> findings = ShopValidator.validate(one("cache", entry("""
                    { "Shop": "General", "Cost": { "Currencies": { "dragon_scale": 5 } },
                      "Rewards": [ { "Kind": "Item", "Params": { "Item": "Ore_Iron" } } ] }
                    """, "cache")), one("general", shop("{}", "General")), Map.of(),
                    WALLETS, null, null, null);

            assertEquals(Severity.WARNING, find(findings, "UNKNOWN_CURRENCY").severity());
        }

        @Test
        void anOfferHandingOverNothingIsAnError() throws Exception {
            List<Finding> findings = ShopValidator.validate(one("cache", entry("""
                    { "Shop": "General", "Cost": { "Currencies": { "bounty_token": 90 } } }
                    """, "cache")), one("general", shop("{}", "General")), Map.of(),
                    WALLETS, null, null, null);

            assertEquals(Severity.ERROR, find(findings, "EMPTY_REWARDS").severity());
        }

        @Test
        void aFreePriceIsReportedRatherThanCharged() throws Exception {
            List<Finding> findings = ShopValidator.validate(one("cache", entry("""
                    { "Shop": "General", "Cost": { "Currencies": { "bounty_token": 0 } },
                      "Rewards": [ { "Kind": "Item", "Params": { "Item": "Ore_Iron" } } ] }
                    """, "cache")), one("general", shop("{}", "General")), Map.of(),
                    WALLETS, null, null, null);

            assertTrue(has(findings, "NON_POSITIVE_COST"));
        }

        @Test
        void authoringBothCadencesOnAShelfIsAnError() throws Exception {
            List<Finding> findings = ShopValidator.validate(Map.of(),
                    one("general", shop("{}", "General")),
                    one("featured", pool("""
                            { "Shop": "General", "Rotation": { "Period": "Daily", "Every": { "Hours": 2 } } }
                            """, "Featured")),
                    WALLETS, null, null, null);

            assertEquals(Severity.ERROR, find(findings, "BOTH_PERIOD_AND_EVERY").severity());
        }

        @Test
        void anEndlessFreeRerollIsCalledOutBecauseItDefeatsTheRotation() throws Exception {
            List<Finding> findings = ShopValidator.validate(Map.of(),
                    one("general", shop("{}", "General")),
                    one("featured", pool("{ \"Shop\": \"General\", \"Reroll\": {} }", "Featured")),
                    WALLETS, null, null, null);

            assertTrue(has(findings, "UNLIMITED_FREE_REROLL"));
        }

        @Test
        void anUnknownFactorInARequiresBlockComesFromTheSharedGateAudit() throws Exception {
            List<Finding> findings = ShopValidator.validate(one("cache", entry("""
                    { "Shop": "General",
                      "Requires": { "Factors": [ { "Factor": "nobody:rank", "Min": 5 } ] },
                      "Rewards": [ { "Kind": "Item", "Params": { "Item": "Ore_Iron" } } ] }
                    """, "cache")), one("general", shop("{}", "General")), Map.of(),
                    WALLETS, null, null, id -> false);

            assertEquals(Severity.WARNING, find(findings, "UNKNOWN_FACTOR").severity());
        }
    }

    // ==================== board ====================

    @Nested
    class Boards {

        @Test
        void aContractNoBoardNamesIsReportedRatherThanSilentlyNeverPosted() throws Exception {
            List<Finding> findings = BoardValidator.validate(Map.of(),
                    one("bounty_lost", bounty("""
                            { "Objectives": { "main": { "Kind": "KILL_ENTITY", "Target": "Trork", "Amount": 1 } },
                              "Rewards": [ { "Kind": "Currency", "Params": { "Currency": "bounty_token" } } ] }
                            """, "Bounty_Lost")),
                    WALLETS, null, null, null, null);

            assertTrue(has(findings, "ORPHANED_BOUNTY"));
        }

        @Test
        void aSkeletonIsNeverReportedAsOrphaned() throws Exception {
            List<Finding> findings = BoardValidator.validate(Map.of(),
                    one("bounty_kill", bounty("""
                            { "Abstract": true,
                              "Objectives": { "main": { "Kind": "KILL_ENTITY", "Amount": 1 } } }
                            """, "Bounty_Kill")),
                    WALLETS, null, null, null, null);

            assertFalse(has(findings, "ORPHANED_BOUNTY"),
                    "a skeleton exists to be inherited from, so having no board is correct");
        }

        @Test
        void aBandNoContractCarriesIsAnUnfillableSlot() throws Exception {
            List<Finding> findings = BoardValidator.validate(
                    one("daily", board("""
                            { "Slots": [ { "Difficulty": "Training" }, { "Difficulty": "Hard" } ] }
                            """, "Daily")),
                    one("bounty_easy", bounty("""
                            { "Boards": [ { "Board": "Daily", "Difficulty": "Training" } ],
                              "Objectives": { "main": { "Kind": "KILL_ENTITY", "Amount": 1 } },
                              "Rewards": [ { "Kind": "Currency", "Params": { "Currency": "bounty_token" } } ] }
                            """, "Bounty_Easy")),
                    WALLETS, null, null, null, null);

            assertEquals(Severity.ERROR, find(findings, "UNFILLABLE_SLOT").severity());
        }

        @Test
        void gatingABandNoSlotEverPostsIsCalledOut() throws Exception {
            List<Finding> findings = BoardValidator.validate(
                    one("daily", board("""
                            { "Slots": [ { "Difficulty": "Training" } ],
                              "AcceptRequires": { "Legendary": { "Factors": [ { "Factor": "hytale:stat",
                                                                                "Param": "MMO_CombatLevel",
                                                                                "Min": 90 } ] } } }
                            """, "Daily")),
                    one("bounty_easy", bounty("""
                            { "Boards": [ { "Board": "Daily", "Difficulty": "Training" } ],
                              "Objectives": { "main": { "Kind": "KILL_ENTITY", "Amount": 1 } },
                              "Rewards": [ { "Kind": "Currency", "Params": { "Currency": "bounty_token" } } ] }
                            """, "Bounty_Easy")),
                    WALLETS, null, null, null, null);

            assertTrue(has(findings, "GATE_ON_UNPOSTED_BAND"));
        }

        @Test
        void aRerollPricedInAWalletNobodyDefinesMeansNobodyCanEverReroll() throws Exception {
            List<Finding> findings = BoardValidator.validate(
                    one("daily", board("""
                            { "Reroll": { "Cost": { "Currencies": { "dragon_scale": 5 } }, "MaxPerPeriod": 3 } }
                            """, "Daily")),
                    one("bounty_easy", bounty("""
                            { "Boards": [ { "Board": "Daily", "Difficulty": "Training" } ],
                              "Objectives": { "main": { "Kind": "KILL_ENTITY", "Amount": 1 } },
                              "Rewards": [ { "Kind": "Currency", "Params": { "Currency": "bounty_token" } } ] }
                            """, "Bounty_Easy")),
                    WALLETS, null, null, null, null);

            assertTrue(has(findings, "MISSING_REROLL_CURRENCY"));
        }

        @Test
        void aBoardNothingPostsToIsAnError() throws Exception {
            List<Finding> findings = BoardValidator.validate(
                    one("daily", board("{}", "Daily")), Map.of(), WALLETS, null, null, null, null);

            assertEquals(Severity.ERROR, find(findings, "EMPTY_BOARD").severity());
        }

        @Test
        void aContractNamingABoardNobodyDefinesIsAWarning() throws Exception {
            List<Finding> findings = BoardValidator.validate(Map.of(),
                    one("bounty_easy", bounty("""
                            { "Boards": [ { "Board": "Nowhere", "Difficulty": "Training" } ],
                              "Objectives": { "main": { "Kind": "KILL_ENTITY", "Amount": 1 } },
                              "Rewards": [ { "Kind": "Currency", "Params": { "Currency": "bounty_token" } } ] }
                            """, "Bounty_Easy")),
                    WALLETS, null, null, null, null);

            assertEquals(Severity.WARNING, find(findings, "UNKNOWN_BOARD").severity());
        }
    }

    // ==================== wallets ====================

    @Nested
    class Wallets {

        @Test
        void aWalletWithNoPictureAtAllIsCalledOut() throws Exception {
            CurrencyAsset bare = decodeCurrency("{}", "bare");
            assertTrue(has(CurrencyValidator.validate("bare", bare), "NO_ICON"));
        }

        @Test
        void anItemBackedWalletNeedsNoIconOfItsOwn() throws Exception {
            CurrencyAsset essence = decodeCurrency("""
                    { "Backing": { "Item": "Ingredient_Life_Essence" } }
                    """, "life_essence");
            assertFalse(has(CurrencyValidator.validate("life_essence", essence), "NO_ICON"));
        }

        @Test
        void aShareWrittenAsAPercentageIsCalledOut() throws Exception {
            CurrencyAsset harsh = decodeCurrency("""
                    { "Icon": "Ingredient_Bar_Gold", "OnDeath": { "LossPercent": 10 } }
                    """, "harsh");
            assertTrue(has(CurrencyValidator.validate("harsh", harsh), "SHARE_OUT_OF_RANGE"));
        }

        @Test
        void aColourNothingCanRenderIsCalledOut() throws Exception {
            CurrencyAsset odd = decodeCurrency("""
                    { "Icon": "Ingredient_Bar_Gold", "Color": "gold" }
                    """, "odd");
            assertTrue(has(CurrencyValidator.validate("odd", odd), "BAD_COLOR"));
        }

        private CurrencyAsset decodeCurrency(String json, String id) throws IOException {
            AssetExtraInfo.Data data = new AssetExtraInfo.Data(CurrencyAsset.class, id, null);
            return CurrencyAsset.CODEC.decodeAndInheritJsonAsset(
                    RawJsonReader.fromJsonString(json), null, new AssetExtraInfo<>(data));
        }
    }
}
