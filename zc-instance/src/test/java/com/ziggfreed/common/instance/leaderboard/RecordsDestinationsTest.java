package com.ziggfreed.common.instance.leaderboard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;
import com.ziggfreed.common.ui.menu.MenuEntry;
import com.ziggfreed.common.ui.menu.MenuSlot;
import com.ziggfreed.common.ui.menu.ZigMenu;
import com.ziggfreed.common.ui.route.Destination;
import com.ziggfreed.common.ui.route.Destinations;

/**
 * The Records tab and destination: generic on purpose (later boards can join it), and claimed unprefixed
 * because the library owns the page.
 */
class RecordsDestinationsTest {

    @BeforeEach
    void seed() {
        Destinations.clearForTests();
        RecordsDestinations.register();
    }

    @AfterEach
    void clear() {
        Destinations.clearForTests();
        ZigMenu.clearForTests();
    }

    @Test
    void theTypeIsClaimedUnprefixed() throws IOException {
        assertTrue(Destinations.isRegistered(RecordsDestinations.TYPE));
        assertInstanceOf(RecordsDestinations.Records.class,
                Destination.CODEC.decodeJson(RawJsonReader.fromJsonString("\"Records\""), new ExtraInfo()));
    }

    @Test
    void theEntryFillsTheRecordsSlot() {
        MenuEntry entry = RecordsDestinations.entry();
        assertEquals(MenuSlot.RECORDS.id(), entry.id());
        assertEquals(RecordsDestinations.TYPE, Destinations.typeIdOf(entry.opens()));
    }
}
