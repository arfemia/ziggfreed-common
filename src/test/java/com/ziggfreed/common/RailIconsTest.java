package com.ziggfreed.common;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.ziggfreed.common.almanac.page.AlmanacMenuTab;
import com.ziggfreed.common.instance.leaderboard.RecordsDestinations;
import com.ziggfreed.common.objectives.book.ObjectiveBookMenu;
import com.ziggfreed.common.ui.menu.MenuEntry;

/**
 * Every library tab on the shared rail wears a picture before its label, drawn from a base-game item's own
 * icon (the widest coverage), and no two tabs share one, so the rail reads at a glance. A tab another branch
 * adds (Settings, Reputation) joins this list when it lands.
 */
class RailIconsTest {

    @Test
    void everyLibraryTabCarriesItsOwnPicture() {
        List<MenuEntry> tabs = List.of(ObjectiveBookMenu.quests(), ObjectiveBookMenu.achievements(),
                AlmanacMenuTab.entry(), RecordsDestinations.entry());
        Set<String> pictures = new HashSet<>();
        for (MenuEntry tab : tabs) {
            assertNotNull(tab.icon(), tab.id() + " has a picture");
            assertFalse(tab.icon().isEmpty(), tab.id() + " has a picture");
            assertNotNull(tab.icon().itemId(), tab.id() + " draws an item's own icon");
            assertTrue(pictures.add(tab.icon().itemId()), tab.id() + " shares its picture with another tab");
        }
    }
}
