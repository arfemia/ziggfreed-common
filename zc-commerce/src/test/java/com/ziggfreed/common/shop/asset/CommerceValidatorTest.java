package com.ziggfreed.common.shop.asset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;
import com.ziggfreed.common.board.asset.BoardAsset;
import com.ziggfreed.common.board.asset.BountyAsset;
import com.ziggfreed.common.board.asset.BoardValidator;
import com.ziggfreed.common.currency.asset.CurrencyAsset;
import com.ziggfreed.common.currency.asset.CurrencyValidator;
import com.ziggfreed.common.i18n.LangCatalog;
import com.ziggfreed.common.loot.reward.CollectingRewardKind;
import com.ziggfreed.common.loot.reward.RewardKinds;
import com.ziggfreed.common.progress.ConventionKeys;
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

        @Test
        void anIncludesLoopIsAnErrorOnEachStorefrontOnItAndAnUnknownIncludeAWarning() throws Exception {
            Map<String, StorefrontAsset> shops = new LinkedHashMap<>();
            shops.put("loop_a", shop("{ \"Currencies\": [\"bounty_token\"], \"Includes\": [\"Loop_B\"] }", "Loop_A"));
            shops.put("loop_b", shop("{ \"Currencies\": [\"bounty_token\"], \"Includes\": [\"Loop_A\", \"Stall\"] }",
                    "Loop_B"));
            shops.put("stall", shop("{ \"Enabled\": false, \"Currencies\": [\"bounty_token\"], "
                    + "\"Includes\": [\"Nowhere\"] }", "Stall"));

            List<Finding> findings = ShopValidator.validate(Map.of(), shops, Map.of(), WALLETS, null, null, null);

            List<Finding> loops = findings.stream().filter(f -> "INCLUDES_CYCLE".equals(f.code())).toList();
            assertEquals(List.of("loop_a", "loop_b"), loops.stream().map(Finding::sourceId).toList(),
                    "each storefront on the loop is told; one merely reached from it is not");
            assertEquals(Severity.ERROR, loops.get(0).severity());
            assertTrue(loops.get(0).message().contains("loop_a -> loop_b -> loop_a"), loops.get(0).message());
            Finding unknown = find(findings, "UNKNOWN_INCLUDE");
            assertEquals(Severity.WARNING, unknown.severity(), "the pack that ships it may not be installed here");
            assertEquals("stall", unknown.sourceId(), "a switched-off storefront's own Includes are still checked");
        }
    }

    // ==================== a reward that pays only inside a pass ====================

    /**
     * A collecting kind is registered in the one shared vocabulary, so it is not unknown; but no
     * purchase and no contract payout ever carries the pass it collects onto, so authored here it
     * would always count lost, and the audit says so.
     */
    @Nested
    class PassOnlyRewards {

        private static final String TALLY = "Test_Tally";

        @BeforeEach
        void registerTheCollectingKind() {
            RewardKinds.shared().register(TALLY, "test", CollectingRewardKind.of(TALLY, StringBuilder.class,
                    (tally, spec) -> tally.append(spec.kind())));
            RewardKinds.shared().register("Test_Plain", "test", (spec, subject) -> { });
        }

        @AfterEach
        void clearTheSharedVocabulary() {
            RewardKinds.clear();
        }

        @Test
        void anOfferPayingOnlyInsideAPassIsAWarning() throws Exception {
            List<Finding> findings = ShopValidator.validate(one("cache", entry("""
                    { "Shop": "General", "Cost": { "Currencies": { "bounty_token": 5 } },
                      "Rewards": [ { "Kind": "Test_Tally" }, { "Kind": "Test_Plain" } ] }
                    """, "cache")), one("general", shop("{}", "General")), Map.of(),
                    WALLETS, RewardKinds.shared()::isRegistered, null, null);

            List<Finding> passOnly = findings.stream()
                    .filter(f -> CollectingRewardKind.SITE_CODE.equals(f.code())).toList();
            assertEquals(1, passOnly.size(), "only the collecting kind is reported: " + findings);
            assertEquals(Severity.WARNING, passOnly.get(0).severity());
            assertEquals(ShopValidator.DOMAIN, passOnly.get(0).domain());
            assertFalse(has(findings, "UNKNOWN_REWARD_KIND"), "a registered kind is not unknown");
        }

        @Test
        void aContractPayingOnlyInsideAPassIsAWarningForEitherRewardList() throws Exception {
            List<Finding> findings = BoardValidator.validate(Map.of(),
                    one("bounty_easy", bounty("""
                            { "Boards": [ { "Board": "Daily", "Difficulty": "Training" } ],
                              "Objectives": { "main": { "Kind": "KILL_ENTITY", "Amount": 1 } },
                              "Rewards": { "Auto": [ { "Kind": "Test_Tally" } ],
                                           "Claim": [ { "Kind": "Test_Tally" }, { "Kind": "Test_Plain" } ] } }
                            """, "Bounty_Easy")),
                    WALLETS, RewardKinds.shared()::isRegistered, null, null, null);

            List<Finding> passOnly = findings.stream()
                    .filter(f -> CollectingRewardKind.SITE_CODE.equals(f.code())).toList();
            assertEquals(2, passOnly.size(), "both reward lists are audited: " + findings);
            assertTrue(passOnly.stream().allMatch(f -> f.severity() == Severity.WARNING
                    && BoardValidator.DOMAIN.equals(f.domain())));
            assertFalse(has(findings, "UNKNOWN_REWARD_KIND"), "a registered kind is not unknown");
        }

        @Test
        void withNoRewardVocabularyTheCheckIsSkippedLikeTheRest() throws Exception {
            List<Finding> findings = ShopValidator.validate(one("cache", entry("""
                    { "Shop": "General", "Cost": { "Currencies": { "bounty_token": 5 } },
                      "Rewards": [ { "Kind": "Test_Tally" } ] }
                    """, "cache")), one("general", shop("{}", "General")), Map.of(),
                    WALLETS, null, null, null);

            assertFalse(has(findings, CollectingRewardKind.SITE_CODE));
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
                              "Rewards": { "Claim": [ { "Kind": "Currency", "Params": { "Currency": "bounty_token" } } ] } }
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
                              "Rewards": { "Claim": [ { "Kind": "Currency", "Params": { "Currency": "bounty_token" } } ] } }
                            """, "Bounty_Easy")),
                    WALLETS, null, null, null, null);

            assertEquals(Severity.ERROR, find(findings, "UNFILLABLE_SLOT").severity());
        }

        @Test
        void anOptionalSlotNoContractCanFillIsANoteAndARequiredOneStaysAnError() throws Exception {
            List<Finding> findings = BoardValidator.validate(
                    one("daily", board("""
                            { "Slots": [ { "Difficulty": "Training" },
                                         { "Difficulty": "Skill", "Optional": true } ] }
                            """, "Daily")),
                    one("bounty_easy", bounty("""
                            { "Boards": [ { "Board": "Daily", "Difficulty": "Training" } ],
                              "Objectives": { "main": { "Kind": "KILL_ENTITY", "Amount": 1 } },
                              "Rewards": { "Claim": [ { "Kind": "Currency", "Params": { "Currency": "bounty_token" } } ] } }
                            """, "Bounty_Easy")),
                    WALLETS, null, null, null, null);

            Finding optional = find(findings, "UNFILLABLE_SLOT");
            assertEquals(Severity.INFO, optional.severity(),
                    "Optional is for a band only some servers fill (a companion mod's contracts), so an empty one is a note");
            assertTrue(optional.message().contains("Skill"), optional.message());
            assertFalse(findings.stream().anyMatch(f -> f.severity() == Severity.ERROR),
                    "the board posts its one required slot every rotation, so nothing here is an error");
        }

        @Test
        void aSlotsRequiresIsAuditedAtItsOwnPath() throws Exception {
            List<Finding> findings = BoardValidator.validate(
                    one("daily", board("""
                            { "Slots": [ { "Difficulty": "Training" },
                                         { "Difficulty": "Training", "Optional": true,
                                           "Requires": { "Factors": [ { "Factor": "nobody:favor", "Min": 1 } ] } } ] }
                            """, "Daily")),
                    one("bounty_easy", bounty("""
                            { "Boards": [ { "Board": "Daily", "Difficulty": "Training" } ],
                              "Objectives": { "main": { "Kind": "KILL_ENTITY", "Amount": 1 } },
                              "Rewards": { "Claim": [ { "Kind": "Currency", "Params": { "Currency": "bounty_token" } } ] } }
                            """, "Bounty_Easy")),
                    WALLETS, null, null, null, "hytale:stat"::equals);

            Finding unknown = find(findings, "UNKNOWN_FACTOR");
            assertEquals(Severity.WARNING, unknown.severity(), "an unknown id is a warning, never an error");
            assertEquals("daily.Slots[1].Requires", unknown.sourceId());
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
                              "Rewards": { "Claim": [ { "Kind": "Currency", "Params": { "Currency": "bounty_token" } } ] } }
                            """, "Bounty_Easy")),
                    WALLETS, null, null, null, null);

            assertTrue(has(findings, "GATE_ON_UNPOSTED_BAND"));
        }

        @Test
        void namingABandNoSlotEverPostsIsCalledOut() throws Exception {
            List<Finding> findings = BoardValidator.validate(
                    one("daily", board("""
                            { "Slots": [ { "Difficulty": "Training" } ],
                              "Grades": { "Skrimish": { "TitleKey": "board.grade.skirmish" } } }
                            """, "Daily")),
                    one("bounty_easy", bounty("""
                            { "Boards": [ { "Board": "Daily", "Difficulty": "Training" } ],
                              "Objectives": { "main": { "Kind": "KILL_ENTITY", "Amount": 1 } },
                              "Rewards": { "Claim": [ { "Kind": "Currency", "Params": { "Currency": "bounty_token" } } ] } }
                            """, "Bounty_Easy")),
                    WALLETS, null, null, null, null);

            assertEquals(Severity.WARNING, find(findings, "NAME_FOR_UNPOSTED_BAND").severity(),
                    "a word nothing ever reads is a warning; the band itself may yet arrive with a pack");
        }

        @Test
        void anUnslottedBoardMayNameItsBands() throws Exception {
            List<Finding> findings = BoardValidator.validate(
                    one("daily", board("""
                            { "Grades": { "Skirmish": { "TitleKey": "board.grade.skirmish" } } }
                            """, "Daily")),
                    one("bounty_easy", bounty("""
                            { "Boards": [ { "Board": "Daily", "Difficulty": "Skirmish" } ],
                              "Objectives": { "main": { "Kind": "KILL_ENTITY", "Amount": 1 } },
                              "Rewards": { "Claim": [ { "Kind": "Currency", "Params": { "Currency": "bounty_token" } } ] } }
                            """, "Bounty_Easy")),
                    WALLETS, null, null, null, null);

            assertFalse(has(findings, "NAME_FOR_UNPOSTED_BAND"),
                    "a board with no slots posts whatever it holds, so every band it names is one it can post");
        }

        @Test
        void aGradeColourThatCannotPaintAsWrittenIsAWarning() throws Exception {
            List<Finding> findings = BoardValidator.validate(
                    one("harvest", board("""
                            { "Slots": [ { "Difficulty": "Feast" }, { "Difficulty": "Dusk" },
                                         { "Difficulty": "Odd" } ],
                              "Grades": { "Feast": { "Color": "#c08a3a" },
                                          "Dusk": { "Color": "#1f2a3a" },
                                          "Odd": { "Color": "orange" } } }
                            """, "Harvest")),
                    Map.of(), WALLETS, null, null, null, null);

            List<Finding> colours = findings.stream()
                    .filter(f -> "UNREADABLE_GRADE_COLOR".equals(f.code())).toList();
            assertEquals(2, colours.size(), "a colour that reads on a row says nothing: " + colours);
            for (Finding colour : colours) {
                assertEquals(Severity.WARNING, colour.severity(),
                        "the board still works; the band paints in the shared accent");
            }
            assertTrue(colours.get(0).message().contains("dusk"), colours.get(0).message());
            assertTrue(colours.get(1).message().contains("odd"), colours.get(1).message());
        }

        @Test
        void aContractWithNoBandOnABoardWhoseSlotsAllNameOneIsCalledOut() throws Exception {
            List<Finding> findings = BoardValidator.validate(
                    one("daily", board("""
                            { "Slots": [ { "Difficulty": "Training" }, { "Difficulty": "Hard" } ] }
                            """, "Daily")),
                    one("bounty_plain", bounty("""
                            { "Boards": [ { "Board": "Daily" } ],
                              "Objectives": { "main": { "Kind": "KILL_ENTITY", "Amount": 1 } },
                              "Rewards": { "Claim": [ { "Kind": "Currency", "Params": { "Currency": "bounty_token" } } ] } }
                            """, "Bounty_Plain")),
                    WALLETS, null, null, null, null);

            assertEquals(Severity.WARNING, find(findings, "MEMBERSHIP_WITHOUT_DIFFICULTY").severity());
        }

        @Test
        void aSlotNamingNoBandTakesAContractWithNoBand() throws Exception {
            List<Finding> findings = BoardValidator.validate(
                    one("nightly", board("""
                            { "Slots": [ { "Count": 1 } ] }
                            """, "Nightly")),
                    one("bounty_plain", bounty("""
                            { "Boards": [ { "Board": "Nightly", "Weight": 1 } ],
                              "Objectives": { "main": { "Kind": "KILL_ENTITY", "Amount": 1 } },
                              "Rewards": { "Claim": [ { "Kind": "Currency", "Params": { "Currency": "bounty_token" } } ] } }
                            """, "Bounty_Plain")),
                    WALLETS, null, null, null, null);

            assertFalse(has(findings, "MEMBERSHIP_WITHOUT_DIFFICULTY"),
                    "a slot with no Difficulty posts anything the board holds, a contract with no band included");
        }

        @Test
        void oneSlotNamingNoBandIsEnoughForAContractWithNoBand() throws Exception {
            List<Finding> findings = BoardValidator.validate(
                    one("daily", board("""
                            { "Slots": [ { "Difficulty": "Training" }, { "Count": 1, "Optional": true } ] }
                            """, "Daily")),
                    Map.of("bounty_easy", bounty("""
                            { "Boards": [ { "Board": "Daily", "Difficulty": "Training" } ],
                              "Objectives": { "main": { "Kind": "KILL_ENTITY", "Amount": 1 } },
                              "Rewards": { "Claim": [ { "Kind": "Currency", "Params": { "Currency": "bounty_token" } } ] } }
                            """, "Bounty_Easy"),
                            "bounty_plain", bounty("""
                            { "Boards": [ { "Board": "Daily" } ],
                              "Objectives": { "main": { "Kind": "KILL_ENTITY", "Amount": 1 } },
                              "Rewards": { "Claim": [ { "Kind": "Currency", "Params": { "Currency": "bounty_token" } } ] } }
                            """, "Bounty_Plain")),
                    WALLETS, null, null, null, null);

            assertFalse(has(findings, "MEMBERSHIP_WITHOUT_DIFFICULTY"),
                    "the slot naming no band can draw the contract naming none");
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
                              "Rewards": { "Claim": [ { "Kind": "Currency", "Params": { "Currency": "bounty_token" } } ] } }
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
                              "Rewards": { "Claim": [ { "Kind": "Currency", "Params": { "Currency": "bounty_token" } } ] } }
                            """, "Bounty_Easy")),
                    WALLETS, null, null, null, null);

            assertEquals(Severity.WARNING, find(findings, "UNKNOWN_BOARD").severity());
        }

        @Test
        void aContractWhoseNameResolvesNowhereIsAWarningOnceACatalogueIsLoaded() throws Exception {
            Map<String, BoardAsset> boards = one("daily", board("""
                    { "Slots": [ { "Difficulty": "Training" } ] }
                    """, "Daily"));
            Map<String, BountyAsset> bounties = one("bounty_easy", bounty("""
                    { "Text": { "TitleKey": "quest.typo.title" },
                      "Boards": [ { "Board": "Daily", "Difficulty": "Training" } ],
                      "Objectives": { "main": { "Kind": "KILL_ENTITY", "Amount": 1 } },
                      "Rewards": { "Claim": [ { "Kind": "Currency", "Params": { "Currency": "bounty_token" } } ] } }
                    """, "Bounty_Easy"));
            try {
                LangCatalog.overrideForTests(Map.of("somepack.unrelated", "x"));
                Finding unnamed = find(BoardValidator.validate(boards, bounties, WALLETS, null, null, null, null),
                        ConventionKeys.UNRESOLVED_TITLE);
                assertNotNull(unnamed);
                assertEquals(Severity.WARNING, unnamed.severity());
                LangCatalog.overrideForTests(Map.of("somepack.quest.bounty_easy.title", "Easy"));
                assertNull(find(BoardValidator.validate(boards, bounties, WALLETS, null, null, null, null),
                        ConventionKeys.UNRESOLVED_TITLE), "quest.<id>.title names a contract as it names a quest");
            } finally {
                LangCatalog.overrideForTests(null);
            }
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
