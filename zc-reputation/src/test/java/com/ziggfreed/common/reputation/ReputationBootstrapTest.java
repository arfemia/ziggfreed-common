package com.ziggfreed.common.reputation;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.ziggfreed.common.factor.FactorContributions;
import com.ziggfreed.common.loot.reward.RewardKinds;
import com.ziggfreed.common.progress.runtime.ProgressionRuntime;

/** The plugin-free half of the module's setup: the three readings, the reward kind and the kill table. */
class ReputationBootstrapTest {

    @AfterEach
    void reset() {
        ProgressionRuntime.resetForTests();
        RewardKinds.clear();
        ReputationFixtures.reset();
    }

    @Test
    void theVocabularyPhaseClaimsTheReadingsTheRewardKindAndTheKillTable() {
        ReputationBootstrap.registerVocabulary();
        assertTrue(FactorContributions.isContributed(ReputationFactors.STANDING));
        assertTrue(FactorContributions.isContributed(ReputationFactors.EARNED));
        assertTrue(FactorContributions.isContributed(ReputationFactors.RANK));
        assertTrue(RewardKinds.shared().isRegistered(ReputationRewardKind.KIND));
        assertTrue(ProgressionRuntime.momentListenerOwners().contains(ReputationBootstrap.OWNER),
                "the kill table hangs on the shared moment stream");
    }
}
