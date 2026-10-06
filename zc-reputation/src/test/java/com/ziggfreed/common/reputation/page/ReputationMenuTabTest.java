package com.ziggfreed.common.reputation.page;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.ziggfreed.common.reputation.FakeReputationNative;
import com.ziggfreed.common.reputation.ReputationFanOut;
import com.ziggfreed.common.reputation.ReputationFixtures;
import com.ziggfreed.common.reputation.ReputationService;
import com.ziggfreed.common.reputation.asset.ReputationConfig;
import com.ziggfreed.common.ui.menu.MenuEntry;
import com.ziggfreed.common.ui.menu.MenuSlot;
import com.ziggfreed.common.ui.route.Destinations;

/**
 * The Reputation tab's one rule (the module is on and the player has met a reputation) and its entry: the
 * Reputation slot, right after Records, with a vanilla banner for its picture.
 */
class ReputationMenuTabTest {

    @AfterEach
    void clear() {
        ReputationFixtures.reset();
        Destinations.clearForTests();
    }

    @Test
    void theTabShowsOnceThePlayerHasMetAReputationWithTheModuleOn() {
        FakeReputationNative engine = ReputationFixtures.engine();
        ReputationService service = new ReputationService(engine, ReputationFanOut.NONE);
        assertFalse(ReputationMenuTab.visible(service, null, null), "nobody met yet");
        engine.stored.put("Test_Old_Jack", 10);
        assertTrue(ReputationMenuTab.visible(service, null, null));
        ReputationConfig.getInstance().setGlobalEnabled(false);
        assertFalse(ReputationMenuTab.visible(service, null, null), "switched off means absent");
        ReputationConfig.getInstance().setGlobalEnabled(true);
        engine.available = false;
        assertFalse(ReputationMenuTab.visible(service, null, null), "no engine reputation plugin, no tab");
    }

    @Test
    void theEntryFillsTheReputationSlotRightAfterRecordsWithABanner() {
        Destinations.clearForTests();
        ReputationDestinations.register();
        MenuEntry entry = ReputationMenuTab.entry();
        assertEquals(MenuSlot.REPUTATION.id(), entry.id());
        assertEquals(MenuSlot.RECORDS.ordinal() + 1, MenuSlot.REPUTATION.ordinal(), "the tab sits right after Records");
        assertNotNull(entry.icon(), "every rail tab carries a picture");
        assertEquals(ReputationMenuTab.ICON_ITEM, entry.icon().itemId());
        assertEquals(ReputationDestinations.TYPE, Destinations.typeIdOf(entry.opens()));
        assertEquals("ziggfreedcommon.reputation.menu.tab", entry.label().getMessageId());
    }
}
