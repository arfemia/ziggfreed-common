package com.ziggfreed.common.reputation.asset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ziggfreed.common.factor.ModGates;
import com.ziggfreed.common.loot.reward.RewardSpec;
import com.ziggfreed.common.reputation.ReputationFixtures;

/**
 * A Beyond payout's reward row gated on a missing mod pays nothing while its ungated siblings pay, and the
 * store's fold (the companions' load, closed by the owner file's read) logs one counted line under the
 * store's contract label, naming the mod and no row.
 */
class ReputationRewardRowGateTest {

    private static final String MMO = "Ziggfreed:MMOSkillTree";
    private static final String BEYOND = "{ \"Beyond\": { \"Every\": 1000, \"Rewards\": ["
            + " { \"Kind\": \"Item\", \"Params\": { \"Item\": \"Harvest_Feast_Pie\", \"Count\": 1 } },"
            + " { \"Kind\": \"Mmo_Xp\", \"Params\": { \"Skill\": \"Cooking\", \"Amount\": 250 },"
            + " \"Requires\": { \"Factors\": [ { \"Factor\": \"hytale:mod_installed\", \"Param\": \"" + MMO + "\","
            + " \"Min\": 1 } ] } } ] } }";

    @TempDir
    Path dir;

    private final List<String> lines = new ArrayList<>();

    @BeforeEach
    void loadTheCompanion() {
        ReputationFixtures.reset();
        ReputationOwnerLayers.setDirectory(dir);
        ReputationFixtures.loadCompanions(Map.of("harvest_feast_growers",
                ReputationFixtures.companion("Harvest_Feast_Growers", BEYOND)));
        ModGates.reportIntoForTests(lines::add);
    }

    @AfterEach
    void restore() {
        ReputationFixtures.reset();
        ModGates.useProbeForTests(null);
        ModGates.reportIntoForTests(null);
    }

    private static void mmoInstalled(boolean installed) {
        ModGates.useProbeForTests(param -> param != null && MMO.equals(param.trim()) ? (installed ? 1.0 : 0.0) : 1.0);
    }

    private static List<String> beyondKinds() {
        return ReputationConfig.getInstance().resolve("Harvest_Feast_Growers").beyondRewards().stream()
                .map(RewardSpec::kind).toList();
    }

    @Test
    void withoutTheModTheGatedRowPaysNothingAndTheFoldCountsIt() {
        mmoInstalled(false);

        ReputationOwnerLayers.reload();

        assertEquals(List.of("Item"), beyondKinds(), "its ungated sibling still pays");
        assertEquals(List.of("[zc] mod gate: Reputations dropped 1 reward row(s) gated on a missing mod (" + MMO + ")"),
                lines);
        assertEquals(ReputationConfig.MOD_GATE_STORE, ReputationConfig.getInstance().modGateStore());
    }

    @Test
    void withTheModTheRowPaysAndNothingIsLogged() {
        mmoInstalled(true);

        ReputationOwnerLayers.reload();

        assertEquals(List.of("Item", "Mmo_Xp"), beyondKinds());
        assertTrue(lines.isEmpty(), "nothing dropped says nothing: " + lines);
    }
}
