package com.ziggfreed.common.commerce.asset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nonnull;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;
import com.ziggfreed.common.board.asset.BoardAsset;
import com.ziggfreed.common.board.asset.BoardValidator;
import com.ziggfreed.common.board.asset.BountyAsset;
import com.ziggfreed.common.currency.asset.CurrencyAsset;
import com.ziggfreed.common.currency.asset.CurrencyValidator;
import com.ziggfreed.common.factor.DerivedFactorAsset;
import com.ziggfreed.common.factor.DerivedFactorValidator;
import com.ziggfreed.common.factor.FactorFormula;
import com.ziggfreed.common.progress.gate.GateSpec;
import com.ziggfreed.common.quest.asset.QuestDefinition;
import com.ziggfreed.common.shop.asset.StorefrontAsset;
import com.ziggfreed.common.shop.asset.ShopEntryAsset;
import com.ziggfreed.common.shop.asset.ShopPoolAsset;
import com.ziggfreed.common.validation.Finding;
import com.ziggfreed.common.validation.Severity;

/**
 * REAL shipped content, converted into these schemas and read back.
 *
 * <p>The point is not that the codecs decode - the codec tests prove that - but that the schemas can
 * express content somebody actually wrote and shipped, with nothing lost on the way across. Each
 * fixture beside this test is a straight conversion of a live file, so a schema that cannot carry one
 * of them is a schema that is wrong, and this is where that shows up rather than during a migration.
 *
 * <p>Deliberately NOT asserted: the numbers. What a contract pays and what a packet costs belong to
 * whoever balances this content, and a test pinning them would make a balance pass a test edit.
 */
class RealContentFixtureTest {

    private static final String ROOT = "/Server/ZiggfreedCommon/";

    @Nonnull
    private static String read(@Nonnull String path) throws IOException {
        try (InputStream in = RealContentFixtureTest.class.getResourceAsStream(ROOT + path)) {
            assertNotNull(in, "fixture missing: " + ROOT + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Nonnull
    private static String idOf(@Nonnull String path) {
        String file = path.substring(path.lastIndexOf('/') + 1);
        return file.substring(0, file.length() - ".json".length());
    }

    private static CurrencyAsset currency(String path) throws IOException {
        String id = idOf(path);
        return CurrencyAsset.CODEC.decodeAndInheritJsonAsset(RawJsonReader.fromJsonString(read(path)), null,
                new AssetExtraInfo<>(new AssetExtraInfo.Data(CurrencyAsset.class, id, null)));
    }

    private static StorefrontAsset shop(String path) throws IOException {
        String id = idOf(path);
        return StorefrontAsset.CODEC.decodeAndInheritJsonAsset(RawJsonReader.fromJsonString(read(path)), null,
                new AssetExtraInfo<>(new AssetExtraInfo.Data(StorefrontAsset.class, id, null)));
    }

    private static ShopPoolAsset pool(String path) throws IOException {
        String id = idOf(path);
        return ShopPoolAsset.CODEC.decodeAndInheritJsonAsset(RawJsonReader.fromJsonString(read(path)), null,
                new AssetExtraInfo<>(new AssetExtraInfo.Data(ShopPoolAsset.class, id, null)));
    }

    private static ShopEntryAsset entry(String path, ShopEntryAsset parent, String parentId)
            throws IOException {
        String id = idOf(path);
        return ShopEntryAsset.CODEC.decodeAndInheritJsonAsset(RawJsonReader.fromJsonString(read(path)),
                parent, new AssetExtraInfo<>(new AssetExtraInfo.Data(ShopEntryAsset.class, id, parentId)));
    }

    private static BoardAsset board(String path) throws IOException {
        String id = idOf(path);
        return BoardAsset.CODEC.decodeAndInheritJsonAsset(RawJsonReader.fromJsonString(read(path)), null,
                new AssetExtraInfo<>(new AssetExtraInfo.Data(BoardAsset.class, id, null)));
    }

    private static BountyAsset bounty(String path, BountyAsset parent, String parentId) throws IOException {
        String id = idOf(path);
        return BountyAsset.CODEC.decodeAndInheritJsonAsset(RawJsonReader.fromJsonString(read(path)),
                parent, new AssetExtraInfo<>(new AssetExtraInfo.Data(BountyAsset.class, id, parentId)));
    }

    // ==================== the two wallets ====================

    @Nested
    class Wallets {

        @Test
        void theCounterBackedTokenSurvivesTheCrossing() throws Exception {
            CurrencyAsset token = currency("Currencies/MMOSkillTree/Bounty_Token.json");

            assertEquals("bounty_token", token.getId());
            assertFalse(token.isItemBacked(), "a token nobody carries in a bag is counter-backed");
            assertNotNull(token.effectiveIconItemId());
            assertNotNull(token.getColor());
            assertTrue(token.isEnabled());
            assertNotNull(token.metaOrEmpty().get("mmoskilltree"),
                    "the three consumer-only knobs ride Meta rather than being argued into the schema");
        }

        @Test
        void theItemBackedWalletNeedsNeitherIconNorNameOfItsOwn() throws Exception {
            CurrencyAsset essence = currency("Currencies/MMOSkillTree/Life_Essence.json");

            assertTrue(essence.isItemBacked());
            assertEquals("Ingredient_Life_Essence", essence.backingItemId());
            assertEquals(essence.backingItemId(), essence.effectiveIconItemId(),
                    "the backing item supplies the picture, so the shipped texture path converts to nothing");
            assertNull(essence.getText(), "and the backing item supplies the name too");
        }

        @Test
        void bothWalletsPassTheirOwnAudit() throws Exception {
            Map<String, CurrencyAsset> wallets = new LinkedHashMap<>();
            wallets.put("bounty_token", currency("Currencies/MMOSkillTree/Bounty_Token.json"));
            wallets.put("life_essence", currency("Currencies/MMOSkillTree/Life_Essence.json"));

            assertNoErrors(CurrencyValidator.validateAll(wallets));
        }
    }

    // ==================== the daily board and three of its contracts ====================

    @Nested
    class Boards {

        @Test
        void theDailyBoardsShapeAndItsPerBandGatesSurvive() throws Exception {
            BoardAsset daily = board("Boards/MMOSkillTree/Daily.json");

            assertEquals("daily", daily.getId());
            assertEquals(4, daily.slotsOrEmpty().length, "four slot lines, one of them yielding two");
            assertEquals(2, daily.slotsOrEmpty()[0].countOrOne(),
                    "the shipped board repeated one filter line twice; Count says the same thing once");
            assertTrue(daily.slotsOrEmpty()[3].isOptional());
            assertEquals(List.of("bounty_token", "life_essence"), daily.currencyIds());
            assertNotNull(daily.getReroll());
            assertFalse(daily.getReroll().costOrFree().isFree());

            GateSpec hard = daily.acceptRequiresFor("hard");
            assertNotNull(hard, "the shipped combat-level map converts to ordinary Requires blocks");
            assertEquals(1, hard.factorsOrEmpty().length);
            assertEquals("hytale:stat", hard.factorsOrEmpty()[0].getFactor());
            assertEquals("MMO_CombatLevel", hard.factorsOrEmpty()[0].getParam());
        }

        @Test
        void aTwoHourlyCadenceIsASpanRatherThanAWordNobodyCanParse() throws Exception {
            BoardAsset bihourly = board("Boards/MMOSkillTree/Bihourly.json");
            RotationAsset rotation = bihourly.getRotation();

            assertNotNull(rotation);
            assertNull(rotation.getPeriod(), "two hours is not a calendar cadence");
            assertNotNull(rotation.getEvery());
            assertFalse(rotation.hasBothCadences());
        }

        @Test
        void aHuntContractInheritsItsStepAndRetunesOneNumber() throws Exception {
            BountyAsset base = bounty("Bounties/MMOSkillTree/Bounty_Kill.json", null, null);
            BountyAsset trork = bounty("Bounties/MMOSkillTree/Bounty_Hunt_Trork.json", base, "bounty_kill");

            assertTrue(base.isAbstract());
            assertFalse(trork.isAbstract());
            assertEquals("KILL_ENTITY", trork.objectivesOrEmpty().get("main").getKind(),
                    "the skeleton's step carries over");
            assertEquals("Trork", trork.objectivesOrEmpty().get("main").getTarget());
            assertEquals("hard", trork.membershipOn("daily").getDifficulty(),
                    "a packed board:/diff:/weight: label list converts to a typed membership");
            assertEquals(2, trork.rewardsOrEmpty().length);
        }

        @Test
        void aFishingContractLeavesItsQuarryUnauthoredAndStillWorks() throws Exception {
            BountyAsset base = bounty("Bounties/MMOSkillTree/Bounty_Fish.json", null, null);
            BountyAsset fishing = bounty("Bounties/MMOSkillTree/Bounty_Daily_Fishing.json", base,
                    "bounty_fish");

            assertNull(fishing.objectivesOrEmpty().get("main").getTarget(),
                    "the shipped file passed an empty target; unauthored says the same thing more plainly");
            assertEquals("CATCH_FISH", fishing.objectivesOrEmpty().get("main").getKind());
            assertEquals("training", fishing.membershipOn("daily").getDifficulty());
        }

        @Test
        void aDeliveryContractKeepsItsWeightBias() throws Exception {
            BountyAsset base = bounty("Bounties/MMOSkillTree/Bounty_TurnIn.json", null, null);
            BountyAsset iron = bounty("Bounties/MMOSkillTree/Bounty_Deliver_Iron.json", base,
                    "bounty_turnin");

            assertEquals("TURN_IN", iron.objectivesOrEmpty().get("main").getKind());
            assertEquals("Ore_Iron", iron.objectivesOrEmpty().get("main").getTarget());
            assertEquals(2.0, iron.membershipOn("daily").weightOrOne());
        }

        @Test
        void everyConvertedContractCarriesTheSamePolicyWithoutAuthoringIt() throws Exception {
            BountyAsset base = bounty("Bounties/MMOSkillTree/Bounty_Kill.json", null, null);
            BountyAsset trork = bounty("Bounties/MMOSkillTree/Bounty_Hunt_Trork.json", base, "bounty_kill");

            QuestDefinition folded = trork.toDefinition(null);
            assertFalse(folded.quest().autoClaim());
            assertTrue(folded.quest().visibility().hidden());
            assertNotNull(folded.turnInAt());
            assertTrue(folded.turnInAt().isAcceptSite());
        }

        @Test
        void theConvertedBoardAndItsContractsPassTheirOwnAudit() throws Exception {
            BountyAsset killBase = bounty("Bounties/MMOSkillTree/Bounty_Kill.json", null, null);
            BountyAsset fishBase = bounty("Bounties/MMOSkillTree/Bounty_Fish.json", null, null);
            BountyAsset turnInBase = bounty("Bounties/MMOSkillTree/Bounty_TurnIn.json", null, null);

            Map<String, BountyAsset> contracts = new LinkedHashMap<>();
            contracts.put("bounty_kill", killBase);
            contracts.put("bounty_fish", fishBase);
            contracts.put("bounty_turnin", turnInBase);
            contracts.put("bounty_hunt_trork",
                    bounty("Bounties/MMOSkillTree/Bounty_Hunt_Trork.json", killBase, "bounty_kill"));
            contracts.put("bounty_daily_fishing",
                    bounty("Bounties/MMOSkillTree/Bounty_Daily_Fishing.json", fishBase, "bounty_fish"));
            contracts.put("bounty_deliver_iron",
                    bounty("Bounties/MMOSkillTree/Bounty_Deliver_Iron.json", turnInBase, "bounty_turnin"));

            Map<String, BoardAsset> boards = new LinkedHashMap<>();
            boards.put("daily", board("Boards/MMOSkillTree/Daily.json"));

            List<Finding> findings = BoardValidator.validate(boards, contracts,
                    id -> List.of("bounty_token", "life_essence").contains(id), null, null, null, null);

            // The daily board's normal band is genuinely unfilled by this slice of the catalogue, so
            // the audit is RIGHT to say so; every other error would be a schema that lost something.
            List<Finding> unexpected = findings.stream()
                    .filter(f -> f.severity() == Severity.ERROR)
                    .filter(f -> !"UNFILLABLE_SLOT".equals(f.code()))
                    .toList();
            assertTrue(unexpected.isEmpty(), "unexpected errors: " + unexpected);
        }
    }

    // ==================== the storefront and one offer family ====================

    @Nested
    class Shops {

        @Test
        void theGeneralStorefrontKeepsItsHeaderAndItsShelfOrder() throws Exception {
            StorefrontAsset general = shop("Shops/MMOSkillTree/General.json");

            assertEquals("general", general.getId());
            assertEquals(List.of("bounty_token", "life_essence"), general.currencyIds());
            assertEquals(List.of("items", "boosts", "conversion", "featured"), general.categoryOrder());
            assertNull(general.getWhere(), "a hub storefront exists in every world, so Where stays unauthored");
        }

        @Test
        void aStandingOfferKeepsItsPriceLimitGateAndPayout() throws Exception {
            ShopEntryAsset boost = entry("ShopEntries/MMOSkillTree/Boost_Mining.json", null, null);

            assertEquals("general", boost.getShop());
            assertEquals("boosts", boost.getListing().categoryId());
            assertEquals(1, boost.costOrFree().currencyAmounts().size());
            assertEquals(3, boost.dailyLimit());
            assertEquals(0, boost.totalLimit(), "the shipped offer had no lifetime cap");
            assertFalse(boost.isPooled());
            assertEquals(1, boost.rewardsOrEmpty().length);

            GateSpec requires = boost.getRequires();
            assertNotNull(requires, "the shipped requiresSkills map converts to a factor bound");
            assertEquals("hytale:stat", requires.factorsOrEmpty()[0].getFactor());
            assertEquals("MMO_Level_MINING", requires.factorsOrEmpty()[0].getParam());
        }

        @Test
        void aFeaturedOfferCarriesItsShelfAndItsBias() throws Exception {
            ShopEntryAsset cache = entry("ShopEntries/MMOSkillTree/Featured_Cache_Copper.json", null, null);

            assertTrue(cache.isPooled());
            assertEquals("featured", cache.getPool().getId());
            assertEquals(2.0, cache.getPool().weightOrOne());
        }

        @Test
        void theExperienceShelfShapesItsDrawByTier() throws Exception {
            ShopPoolAsset shelf = pool("ShopPools/MMOSkillTree/XpExchange.json");

            assertEquals("xpexchange", shelf.getShop());
            assertEquals(3, shelf.slotsOrEmpty().length);
            assertEquals(List.of("lesser", "greater", "master"),
                    List.of(shelf.slotsOrEmpty()[0].label(), shelf.slotsOrEmpty()[1].label(),
                            shelf.slotsOrEmpty()[2].label()));
            assertNotNull(shelf.getReroll());
        }

        @Test
        void theExperiencePacketSkeletonCarriesEverythingTheFamilyShares() throws Exception {
            ShopEntryAsset skeleton = entry("ShopEntries/MMOSkillTree/Xp_Packet.json", null, null);

            assertTrue(skeleton.isAbstract());
            assertEquals("xpexchange", skeleton.getShop());
            assertEquals("conversion", skeleton.getListing().categoryId());
            assertTrue(skeleton.costOrFree().isFree(),
                    "no price on the skeleton: every number differs per skill and per size");
        }
    }

    // ==================== the factor a pack mints with no code ====================

    @Test
    void aPackMintedFactorDefinitionReadsBackAndPassesItsAudit() throws Exception {
        String id = "bounty_veteran";
        DerivedFactorAsset asset = DerivedFactorAsset.CODEC.decodeAndInheritJsonAsset(
                RawJsonReader.fromJsonString(read("Factors/Bounty_Veteran.json")), null,
                new AssetExtraInfo<>(new AssetExtraInfo.Data(DerivedFactorAsset.class, id, null)));

        assertNotNull(asset.getFormula());
        assertFalse(asset.getFormula().isEmpty(),
                "an empty definition would read as no definition, which fails every gate written on it");

        Map<String, FactorFormula> pool = new LinkedHashMap<>();
        pool.put(id, asset.getFormula());
        // Its terms name portable engine readings, so nothing here has to be registered for the
        // definition itself to be sound.
        assertNoErrors(DerivedFactorValidator.validateAll(pool, factor -> true));
    }

    private static void assertNoErrors(@Nonnull List<Finding> findings) {
        List<Finding> errors = findings.stream().filter(f -> f.severity() == Severity.ERROR).toList();
        assertTrue(errors.isEmpty(), "converted content reported errors: " + errors);
    }
}
