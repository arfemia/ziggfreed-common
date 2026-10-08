package com.ziggfreed.common.quest.asset;

import static com.ziggfreed.common.quest.asset.QuestAssetCodecTest.decode;
import static com.ziggfreed.common.quest.asset.QuestAssetCodecTest.decodeRoot;
import static com.ziggfreed.common.quest.asset.QuestAssetStoreContributionTest.codes;
import static com.ziggfreed.common.quest.asset.QuestGeneratorTest.generator;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ziggfreed.common.factor.ModGates;
import com.ziggfreed.common.loot.reward.RewardKindRegistry;
import com.ziggfreed.common.loot.reward.RewardSpec;
import com.ziggfreed.common.validation.Finding;

/**
 * Review Focus 1 for one reward row: a quest that pays an item and, only where the companion mod runs, a
 * row of that mod's kind. Without the mod the row is absent from the folded quest, so nothing pays it,
 * no audit names it, and the store's fold logs one counted line naming the mod and no row; with the mod
 * the same row pays as written and nothing is logged. A skeleton's rows are counted only through the
 * children that fold, a generated child counts its inherited row, and every fold logs its own total.
 */
class QuestRewardRowGateTest {

    private static final QuestAssetStore STORE = QuestAssetStore.getInstance();

    private static final String MMO = "Ziggfreed:MMOSkillTree";
    private static final String GATE = "\"Requires\": { \"Factors\": [ { \"Factor\": \"hytale:mod_installed\","
            + " \"Param\": \"" + MMO + "\", \"Min\": 1 } ] }";
    private static final String PIE = "{ \"Kind\": \"Item\", \"Params\": { \"Item\": \"Harvest_Feast_Pie\", \"Count\": 1 } }";
    private static final String XP = "{ \"Kind\": \"Mmo_Xp\", \"Params\": { \"Skill\": \"Cooking\", \"Amount\": 250 }, "
            + GATE + " }";

    /** Only the framework's Item kind pays here, as on a server without the companion mod. */
    private static final RewardKindRegistry REWARDS = new RewardKindRegistry();

    static {
        REWARDS.register("Item", (spec, subject) -> { });
    }

    private final List<String> lines = new ArrayList<>();

    @BeforeEach
    void emptyStoreAndCaptureTheDropLines() {
        emptyStore();
        ModGates.reportIntoForTests(lines::add);
    }

    @AfterEach
    void restore() {
        emptyStore();
        ModGates.useProbeForTests(null);
        ModGates.reportIntoForTests(null);
    }

    private static void emptyStore() {
        STORE.mergeQuests(Map.of());
        STORE.mergeGenerators(Map.of());
        STORE.mergeContributed(Map.of());
    }

    private static void mmoInstalled(boolean installed) {
        ModGates.useProbeForTests(param -> param != null && MMO.equals(param.trim()) ? (installed ? 1.0 : 0.0) : 1.0);
    }

    private static String quest(String rewards) {
        return "{ \"Objectives\": { \"collect\": { \"Kind\": \"PICKUP_ITEM\", \"Target\": \"Wheat\", \"Amount\": 1 } },"
                + " \"Rewards\": " + rewards + " }";
    }

    /** The two Harvest Feast quests: an item plus a gated row to collect, and a gated row on the spot. */
    private static void loadTheFeast() throws Exception {
        Map<String, QuestAsset> files = new LinkedHashMap<>();
        files.put("harvest_feast_pie", decodeRoot(quest("{ \"Claim\": [ " + PIE + ", " + XP + " ] }"), "Harvest_Feast_Pie"));
        files.put("harvest_feast_bake", decodeRoot(quest("{ \"Auto\": [ " + XP + " ] }"), "Harvest_Feast_Bake"));
        STORE.mergeQuests(files);
    }

    private static List<String> kinds(List<RewardSpec> specs) {
        return specs.stream().map(RewardSpec::kind).toList();
    }

    private static String rowLine(int rows) {
        return "[zc] mod gate: Quests dropped " + rows + " reward row(s) gated on a missing mod (" + MMO + ")";
    }

    @Test
    void withoutTheModAGatedRowPaysNothingIsAuditedNowhereAndOneFoldCountsIt() throws Exception {
        mmoInstalled(false);
        loadTheFeast();

        QuestAssetStore.Resolution resolution = STORE.resolve(null);

        QuestDefinition pie = resolution.pool().definition("harvest_feast_pie");
        QuestDefinition bake = resolution.pool().definition("harvest_feast_bake");
        assertNotNull(pie);
        assertNotNull(bake);
        assertEquals(List.of("Item"), kinds(pie.quest().claimRewards()), "its ungated sibling still pays");
        assertEquals(List.of(), kinds(bake.quest().autoRewards()));
        List<Finding> audit = QuestPoolValidator.validate(resolution.pool(), null, REWARDS, null, null);
        assertFalse(codes(audit).contains("UNKNOWN_REWARD_KIND"), "no audit reports the absent row: " + audit);
        assertEquals(List.of(rowLine(2)), lines, "one counted line for the store, naming the mod");
        for (String line : lines) {
            assertFalse(line.toLowerCase(Locale.ROOT).contains("mmo_"), "a drop line names no row: " + line);
        }
    }

    @Test
    void withTheModTheRowPaysAsWrittenAndNothingIsLogged() throws Exception {
        mmoInstalled(true);
        loadTheFeast();

        QuestAssetStore.Resolution resolution = STORE.resolve(null);

        assertEquals(List.of("Item", "Mmo_Xp"),
                kinds(resolution.pool().definition("harvest_feast_pie").quest().claimRewards()));
        assertEquals(List.of("Mmo_Xp"), kinds(resolution.pool().definition("harvest_feast_bake").quest().autoRewards()));
        assertTrue(codes(QuestPoolValidator.validate(resolution.pool(), null, REWARDS, null, null))
                .contains("UNKNOWN_REWARD_KIND"), "a loaded row is audited like any other");
        assertTrue(lines.isEmpty(), "nothing dropped says nothing: " + lines);
    }

    @Test
    void aFoldWithNothingGatedLogsNothing() throws Exception {
        mmoInstalled(false);
        STORE.mergeQuests(Map.of("harvest_feast_plain",
                decodeRoot(quest("{ \"Claim\": [ " + PIE + " ] }"), "Harvest_Feast_Plain")));

        STORE.resolve(null);

        assertTrue(lines.isEmpty(), "nothing dropped says nothing: " + lines);
    }

    @Test
    void everyFoldLogsItsOwnTotalSoTheLastLineWins() throws Exception {
        mmoInstalled(false);
        loadTheFeast();

        STORE.resolve(null);
        STORE.resolve(null);

        assertEquals(List.of(rowLine(2), rowLine(2)), lines, "a re-fold repeats its total, never adds to it");
    }

    @Test
    void aSkeletonCountsThroughTheChildrenThatFoldAndAGeneratedChildCountsItsInheritedRow() throws Exception {
        mmoInstalled(false);
        QuestAsset skeleton = decodeRoot("{ \"Abstract\": true, \"Rewards\": { \"Claim\": [ " + PIE + ", " + XP + " ] },"
                + " \"Objectives\": { \"collect\": { \"Kind\": \"PICKUP_ITEM\", \"Target\": \"Wheat\", \"Amount\": 1 } } }",
                "Harvest_Feast_Base");
        QuestAsset child = decode("{ }", "Harvest_Feast_Child", "Harvest_Feast_Base", skeleton);
        Map<String, QuestAsset> files = new LinkedHashMap<>();
        files.put("harvest_feast_base", skeleton);
        files.put("harvest_feast_child", child);
        STORE.mergeQuests(files);
        STORE.mergeGenerators(Map.of("harvest_ladder", generator("{ \"Base\": \"harvest_feast_child\","
                + " \"IdPattern\": \"harvest_feast_child_{crop}\","
                + " \"ForEach\": [ { \"Token\": \"crop\", \"Values\": [\"wheat\"] } ],"
                + " \"Child\": { \"Objectives\": { \"collect\": { \"Target\": \"{crop}\" } } } }", "harvest_ladder")));

        QuestAssetStore.Resolution resolution = STORE.resolve(null);

        assertEquals(List.of("Item"), kinds(resolution.pool().definition("harvest_feast_child").quest().claimRewards()),
                "the child inherits the row and its gate together");
        assertEquals(List.of("Item"),
                kinds(resolution.pool().definition("harvest_feast_child_wheat").quest().claimRewards()));
        assertEquals(List.of(rowLine(2)), lines, "the child and its generated sibling, never the skeleton");
    }
}
