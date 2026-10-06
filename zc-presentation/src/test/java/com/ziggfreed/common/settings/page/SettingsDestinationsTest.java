package com.ziggfreed.common.settings.page;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;
import com.ziggfreed.common.ui.menu.MenuEntry;
import com.ziggfreed.common.ui.menu.MenuSlot;
import com.ziggfreed.common.ui.route.Destination;
import com.ziggfreed.common.ui.route.DestinationContext;
import com.ziggfreed.common.ui.route.Destinations;

/**
 * The Settings tab is one word in any file, the menu's Settings slot right after Records, shown to every
 * player, with a picture.
 */
class SettingsDestinationsTest {

    @BeforeEach
    @AfterEach
    void clear() {
        Destinations.clearForTests();
    }

    @Test
    void aFileNamesTheSettingsTabByItsOneWord() throws IOException {
        SettingsDestinations.register();

        assertTrue(Destinations.isRegistered(SettingsDestinations.TYPE));
        Destination decoded = Destination.CODEC.decodeJson(RawJsonReader.fromJsonString("\"Settings\""), new ExtraInfo());
        assertInstanceOf(SettingsDestinations.Settings.class, decoded);
        assertEquals(SettingsDestinations.TYPE, Destinations.typeIdOf(SettingsDestinations.SETTINGS));
    }

    @Test
    void theSettingsSlotIsTheRailsLastAfterRecords() {
        MenuSlot[] slots = MenuSlot.values();
        assertTrue(MenuSlot.SETTINGS.ordinal() > MenuSlot.RECORDS.ordinal(), "Settings sits below Records");
        assertEquals(MenuSlot.SETTINGS, slots[slots.length - 1],
                "and ends the rail (Reputation, when it lands, goes between them)");
        assertEquals("settings", MenuSlot.SETTINGS.id());
    }

    @Test
    void theTabIsTheSlotsOwnWithAPictureForEveryPlayer() {
        MenuEntry entry = SettingsDestinations.entry();

        assertEquals(MenuSlot.SETTINGS.id(), entry.id());
        assertSame(SettingsDestinations.SETTINGS, entry.opens());
        assertNotNull(entry.icon());
        assertEquals(SettingsDestinations.TAB_ICON, entry.icon().itemId());
        assertTrue(entry.visible().test(new DestinationContext(null, null, null, null, null, null)));
    }
}
