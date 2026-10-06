package com.ziggfreed.common.reputation;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.ziggfreed.common.factor.FactorContributions;
import com.ziggfreed.common.loot.reward.RewardKinds;
import com.ziggfreed.common.progress.runtime.ProgressionRuntime;
import com.ziggfreed.common.reputation.page.ReputationDestinations;
import com.ziggfreed.common.ui.menu.MenuSlot;
import com.ziggfreed.common.ui.menu.ZigMenu;
import com.ziggfreed.common.ui.route.Destinations;

/**
 * The plugin-free phases of the module's setup: the three readings, the reward kind and the kill table, then
 * the Reputation destination and its tab in the shared menu.
 */
class ReputationBootstrapTest {

    @AfterEach
    void reset() {
        ProgressionRuntime.resetForTests();
        RewardKinds.clear();
        ReputationFixtures.reset();
        Destinations.clearForTests();
        ZigMenu.clearForTests();
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

    @Test
    void theMenuPhaseRegistersTheDestinationAndFillsTheTab() {
        ReputationBootstrap.registerMenu();
        assertTrue(Destinations.isRegistered(ReputationDestinations.TYPE),
                "registered at setup, before any conversation naming it decodes");
        assertNotNull(ZigMenu.slot(MenuSlot.REPUTATION), "the module fills its tab");
    }
}
