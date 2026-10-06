package com.ziggfreed.common.almanac;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.ziggfreed.common.almanac.page.AlmanacDestinations;
import com.ziggfreed.common.factor.FeatureFlags;
import com.ziggfreed.common.progress.runtime.ProgressionRuntime;
import com.ziggfreed.common.ui.menu.MenuSlot;
import com.ziggfreed.common.ui.menu.ZigMenu;
import com.ziggfreed.common.ui.route.Destinations;

/** The plugin-free half of the Almanac's setup: the feature, the destination, the menu tab and the counter. */
class AlmanacBootstrapTest {

    @AfterEach
    void reset() {
        ProgressionRuntime.resetForTests();
        FeatureFlags.reset();
        Destinations.clearForTests();
        AlmanacSwitch.resetForTests();
        ZigMenu.clearForTests();
    }

    @Test
    void theVocabularyPhaseClaimsTheFeatureTheDestinationAndTheCounter() {
        AlmanacBootstrap.registerVocabulary();

        assertTrue(FeatureFlags.isKnown(AlmanacSwitch.NAMESPACE, AlmanacSwitch.FEATURE));
        assertTrue(Destinations.isRegistered(AlmanacDestinations.TYPE));
        assertNotNull(ZigMenu.slot(MenuSlot.ALMANAC), "the Almanac fills its menu tab");
        assertTrue(ProgressionRuntime.momentListenerOwners().contains(AlmanacBootstrap.OWNER),
                "the Almanac counts off the shared moment stream");
    }
}
