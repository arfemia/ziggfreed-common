package com.ziggfreed.common.shop.asset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ziggfreed.common.board.asset.BoardAssetStore;
import com.ziggfreed.common.board.asset.BoardValidator;
import com.ziggfreed.common.board.asset.BountyAsset;
import com.ziggfreed.common.commerce.fold.ShopEntryOffer;
import com.ziggfreed.common.factor.ModGates;
import com.ziggfreed.common.loot.reward.RewardSpec;
import com.ziggfreed.common.quest.asset.QuestDefinition;
import com.ziggfreed.common.validation.Finding;

/**
 * Review Focus 1 for one reward row on an offer and on a contract: without the companion mod the gated
 * row is absent from what the purchase or the contract pays and from every audit line (no
 * {@code UNKNOWN_REWARD_KIND}, no {@code BLANK_REWARD}), an offer or contract whose every row is absent
 * reads as paying nothing, and each store's fold logs one counted line naming the mod and no row. With
 * the mod the same row pays and is audited like any other.
 */
class RewardRowGateTest {

    private static final String MMO = "Ziggfreed:MMOSkillTree";
    private static final String GATE = "\"Requires\": { \"Factors\": [ { \"Factor\": \"hytale:mod_installed\","
            + " \"Param\": \"" + MMO + "\", \"Min\": 1 } ] }";
    private static final String PIE = "{ \"Kind\": \"Item\", \"Params\": { \"Item\": \"Harvest_Feast_Pie\", \"Count\": 1 } }";
    private static final String XP = "{ \"Kind\": \"Mmo_Xp\", \"Params\": { \"Skill\": \"Cooking\", \"Amount\": 250 }, "
            + GATE + " }";
    private static final String STEP = "\"Objectives\": { \"main\": { \"Kind\": \"KILL_ENTITY\", \"Amount\": 1 } }";

    private static final ShopValidator.CurrencyProbe WALLETS = "harvest_coin"::equals;
    /** Only the framework's Item kind pays here, as on a server without the companion mod. */
    private static final Predicate<String> ITEM_ONLY = "Item"::equalsIgnoreCase;

    private final List<String> lines = new ArrayList<>();

    @BeforeEach
    void captureTheDropLines() {
        ModGates.reportIntoForTests(lines::add);
    }

    @AfterEach
    void restore() {
        ShopAssetStore.getInstance().mergeEntries(Map.of());
        ShopAssetStore.getInstance().mergeGenerators(Map.of());
        BoardAssetStore.getInstance().merge(Map.of());
        ModGates.useProbeForTests(null);
        ModGates.reportIntoForTests(null);
    }

    private static void mmoInstalled(boolean installed) {
        ModGates.useProbeForTests(param -> param != null && MMO.equals(param.trim()) ? (installed ? 1.0 : 0.0) : 1.0);
    }

    private static ShopEntryAsset offer(String id, String rewards) throws Exception {
        return CommerceValidatorTest.entry("{ \"Shop\": \"Harvest_Feast_Stall\","
                + " \"Cost\": { \"Currencies\": { \"harvest_coin\": 5 } }, \"Rewards\": " + rewards + " }", id);
    }

    private static BountyAsset contract(String id, String rewards) throws Exception {
        return CommerceValidatorTest.bounty("{ \"Boards\": [ { \"Board\": \"Harvest_Feast_Board\","
                + " \"Difficulty\": \"Normal\" } ], " + STEP + ", \"Rewards\": " + rewards + " }", id);
    }

    private static <T> Map<String, T> one(String id, T value) {
        Map<String, T> map = new LinkedHashMap<>();
        map.put(id, value);
        return map;
    }

    private static List<Finding> auditOffers(Map<String, ShopEntryAsset> offers) throws Exception {
        return ShopValidator.validate(offers, one("harvest_feast_stall", CommerceValidatorTest.shop("{}",
                "Harvest_Feast_Stall")), Map.of(), WALLETS, ITEM_ONLY, null, null);
    }

    private static List<Finding> auditContracts(Map<String, BountyAsset> contracts) {
        return BoardValidator.validate(Map.of(), contracts, WALLETS, ITEM_ONLY, null, null, null);
    }

    private static Set<String> codes(List<Finding> findings) {
        return findings.stream().map(Finding::code).collect(Collectors.toSet());
    }

    private static List<String> kinds(List<RewardSpec> specs) {
        return specs.stream().map(RewardSpec::kind).toList();
    }

    private static String rowLine(String store, int rows) {
        return "[zc] mod gate: " + store + " dropped " + rows + " reward row(s) gated on a missing mod (" + MMO + ")";
    }

    @Test
    void withoutTheModAnOffersGatedRowIsAbsentFromTheSaleAndTheAudit() throws Exception {
        ShopEntryAsset pie = offer("Harvest_Feast_Pie_Offer", "[ " + PIE + ", " + XP + " ]");

        mmoInstalled(false);
        Set<String> without = codes(auditOffers(one("harvest_feast_pie_offer", pie)));

        assertEquals(List.of("Item"), kinds(ShopEntryOffer.of(pie).rewards()), "the purchase hands over the pie alone");
        assertFalse(without.contains("UNKNOWN_REWARD_KIND"), "audit: " + without);
        assertFalse(without.contains("BLANK_REWARD"));
        assertFalse(without.contains("EMPTY_REWARDS"), "the pie is still on offer");

        mmoInstalled(true);
        assertEquals(List.of("Item", "Mmo_Xp"), kinds(ShopEntryOffer.of(pie).rewards()));
        assertTrue(codes(auditOffers(one("harvest_feast_pie_offer", pie))).contains("UNKNOWN_REWARD_KIND"),
                "a loaded row is audited like any other");
    }

    @Test
    void anOfferWhoseEveryRowIsAbsentHandsOverNothing() throws Exception {
        ShopEntryAsset token = offer("Harvest_Feast_Xp_Offer", "[ " + XP + " ]");

        mmoInstalled(false);
        assertTrue(codes(auditOffers(one("harvest_feast_xp_offer", token))).contains("EMPTY_REWARDS"),
                "a sale that pays nothing here is worth saying; such an offer belongs behind a file gate");

        mmoInstalled(true);
        assertFalse(codes(auditOffers(one("harvest_feast_xp_offer", token))).contains("EMPTY_REWARDS"));
    }

    @Test
    void withoutTheModAContractsGatedRowIsAbsentFromItsPayoutAndTheAudit() throws Exception {
        BountyAsset job = contract("Harvest_Feast_Job", "{ \"Claim\": [ " + PIE + ", " + XP + " ], \"Auto\": [ " + XP + " ] }");
        BountyAsset xpOnly = contract("Harvest_Feast_Xp_Job", "{ \"Auto\": [ " + XP + " ] }");

        mmoInstalled(false);
        QuestDefinition definition = job.toDefinition(null);
        List<Finding> audit = auditContracts(one("harvest_feast_job", job));

        assertEquals(List.of("Item"), kinds(definition.quest().claimRewards()));
        assertEquals(List.of(), kinds(definition.quest().autoRewards()));
        assertFalse(codes(audit).contains("UNKNOWN_REWARD_KIND"), "audit: " + audit);
        assertFalse(codes(audit).contains("BLANK_REWARD"));
        assertFalse(codes(audit).contains("EMPTY_REWARDS"));
        assertTrue(codes(auditContracts(one("harvest_feast_xp_job", xpOnly))).contains("EMPTY_REWARDS"),
                "a contract that pays nothing here is worth saying");

        mmoInstalled(true);
        assertEquals(List.of("Item", "Mmo_Xp"), kinds(job.toDefinition(null).quest().claimRewards()));
        assertTrue(codes(auditContracts(one("harvest_feast_job", job))).contains("UNKNOWN_REWARD_KIND"));
    }

    @Test
    void eachStoresFoldCountsTheRowsItLeftOutUnderItsLabel() throws Exception {
        mmoInstalled(false);
        ShopAssetStore.getInstance().mergeEntries(Map.of(
                "harvest_feast_pie_offer", offer("Harvest_Feast_Pie_Offer", "[ " + PIE + ", " + XP + " ]"),
                "harvest_feast_plain_offer", offer("Harvest_Feast_Plain_Offer", "[ " + PIE + " ]")));
        BoardAssetStore.getInstance().merge(Map.of("harvest_feast_job",
                contract("Harvest_Feast_Job", "{ \"Claim\": [ " + PIE + ", " + XP + " ], \"Auto\": [ " + XP + " ] }")));

        ShopAssetStore.getInstance().resolve(null);
        BoardAssetStore.getInstance().resolve();

        assertEquals(List.of(rowLine(ShopAssetStore.MOD_GATE_STORE, 1), rowLine(BoardAssetStore.MOD_GATE_STORE, 2)),
                lines, "one counted line per store, naming the mod and no row");

        lines.clear();
        mmoInstalled(true);
        ShopAssetStore.getInstance().resolve(null);
        BoardAssetStore.getInstance().resolve();
        assertTrue(lines.isEmpty(), "nothing dropped says nothing: " + lines);
    }
}
