package com.ziggfreed.common.achievement.asset;

import static com.ziggfreed.common.achievement.asset.AchievementAssetCodecTest.decodeRoot;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;
import com.ziggfreed.common.factor.ModGates;
import com.ziggfreed.common.loot.reward.RewardKindRegistry;
import com.ziggfreed.common.loot.reward.RewardSpec;
import com.ziggfreed.common.progress.asset.ContentRewardsAsset;
import com.ziggfreed.common.validation.Finding;

/**
 * An achievement's and a points milestone's reward row gated on a missing mod is absent from what the
 * fold hands the engine and from the audit, and each store's fold logs one counted line under its
 * contract label, naming the mod and no row; with the mod the rows pay and nothing is logged.
 */
class AchievementRewardRowGateTest {

    private static final AchievementAssetStore STORE = AchievementAssetStore.getInstance();
    private static final AchievementMilestoneConfig MILESTONES = AchievementMilestoneConfig.getInstance();

    private static final String MMO = "Ziggfreed:MMOSkillTree";
    private static final String GATE = "\"Requires\": { \"Factors\": [ { \"Factor\": \"hytale:mod_installed\","
            + " \"Param\": \"" + MMO + "\", \"Min\": 1 } ] }";
    private static final String PIE = "{ \"Kind\": \"Item\", \"Params\": { \"Item\": \"Harvest_Feast_Pie\", \"Count\": 1 } }";
    private static final String TOKEN = "{ \"Kind\": \"Mmo_Boost_Token\", \"Params\": { \"Token\": \"Harvest\" }, "
            + GATE + " }";
    private static final String CRITERIA = "\"Criteria\": { \"a\": { \"Kind\": \"PICKUP_ITEM\", \"Target\": \"Wheat\" } }";

    private static final RewardKindRegistry REWARDS = new RewardKindRegistry();

    static {
        REWARDS.register("Item", (spec, subject) -> { });
    }

    private final List<String> lines = new ArrayList<>();

    @BeforeEach
    void captureTheDropLines() {
        ModGates.reportIntoForTests(lines::add);
    }

    @AfterEach
    void restore() {
        STORE.merge(Map.of());
        MILESTONES.mergePackLayer(Map.of());
        ModGates.useProbeForTests(null);
        ModGates.reportIntoForTests(null);
    }

    private static void mmoInstalled(boolean installed) {
        ModGates.useProbeForTests(param -> param != null && MMO.equals(param.trim()) ? (installed ? 1.0 : 0.0) : 1.0);
    }

    private static void loadTheFeast() throws Exception {
        Map<String, AchievementAsset> files = new LinkedHashMap<>();
        files.put("harvest_feast_baker", decodeRoot("{ " + CRITERIA + ", \"Rewards\": { \"Claim\": [ "
                + PIE + ", " + TOKEN + " ], \"Auto\": [ " + TOKEN + " ] } }", "Harvest_Feast_Baker"));
        STORE.merge(files);
    }

    private static ContentRewardsAsset rewards(String json) throws IOException {
        return ContentRewardsAsset.CODEC.decodeJson(RawJsonReader.fromJsonString(json), new ExtraInfo());
    }

    private static List<String> kinds(List<RewardSpec> specs) {
        return specs.stream().map(RewardSpec::kind).toList();
    }

    private static String rowLine(String store, int rows) {
        return "[zc] mod gate: " + store + " dropped " + rows + " reward row(s) gated on a missing mod (" + MMO + ")";
    }

    @Test
    void withoutTheModAnAchievementsGatedRowIsAbsentAndItsFoldCountsIt() throws Exception {
        mmoInstalled(false);
        loadTheFeast();

        AchievementAssetStore.Resolution resolution = STORE.resolve();

        AchievementDefinition baker = resolution.pool().definition("harvest_feast_baker");
        assertEquals(List.of("Item"), kinds(baker.achievement().claimRewards()));
        assertEquals(List.of(), kinds(baker.achievement().autoRewards()));
        List<Finding> audit = AchievementPoolValidator.validate(resolution.pool(), null, REWARDS, null, null);
        assertFalse(audit.stream().anyMatch(f -> "UNKNOWN_REWARD_KIND".equals(f.code())), "audit: " + audit);
        assertEquals(List.of(rowLine(AchievementAssetStore.MOD_GATE_STORE, 2)), lines);
    }

    @Test
    void withTheModTheAchievementsRowsPayAndNothingIsLogged() throws Exception {
        mmoInstalled(true);
        loadTheFeast();

        AchievementDefinition baker = STORE.resolve().pool().definition("harvest_feast_baker");

        assertEquals(List.of("Item", "Mmo_Boost_Token"), kinds(baker.achievement().claimRewards()));
        assertTrue(lines.isEmpty(), "nothing dropped says nothing: " + lines);
    }

    @Test
    void aMilestonesGatedRowIsAbsentFromItsRungAndItsFoldCountsIt() throws Exception {
        mmoInstalled(false);

        MILESTONES.mergePackLayer(Map.of(
                "harvest_100", AchievementMilestoneAsset.of("harvest_100", 100, null, null,
                        rewards("{ \"Claim\": [ " + PIE + ", " + TOKEN + " ] }")),
                "harvest_200", AchievementMilestoneAsset.of("harvest_200", 200, null, null,
                        rewards("{ \"Claim\": [ " + PIE + " ] }"))));

        assertEquals(List.of("Item"), kinds(MILESTONES.milestones().get(0).claimRewards()));
        assertEquals(List.of(rowLine(AchievementMilestoneConfig.MOD_GATE_STORE, 1)), lines);
        assertEquals("AchievementMilestones", MILESTONES.modGateStore(), "the store's contract label");

        lines.clear();
        MILESTONES.mergePackLayer(Map.of("harvest_200", AchievementMilestoneAsset.of("harvest_200", 200, null, null,
                rewards("{ \"Claim\": [ " + PIE + " ] }"))));
        assertTrue(lines.isEmpty(), "a fold with nothing gated says nothing: " + lines);
    }
}
