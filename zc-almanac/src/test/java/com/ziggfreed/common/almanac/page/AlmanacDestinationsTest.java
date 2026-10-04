package com.ziggfreed.common.almanac.page;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;
import com.ziggfreed.common.almanac.AlmanacSwitch;
import com.ziggfreed.common.ui.route.Destination;
import com.ziggfreed.common.ui.route.DestinationContext;
import com.ziggfreed.common.ui.route.Destinations;

/**
 * The {@code Almanac} destination: what content may write, and that opening it with nothing behind it
 * declines without throwing. The screen itself is in-game smoke territory.
 */
class AlmanacDestinationsTest {

    @BeforeEach
    void seed() {
        Destinations.clearForTests();
        AlmanacDestinations.register();
        AlmanacSwitch.resetForTests();
    }

    @AfterEach
    void clear() {
        Destinations.clearForTests();
        AlmanacSwitch.resetForTests();
    }

    private static Destination decode(String json) throws IOException {
        return Destination.CODEC.decodeJson(RawJsonReader.fromJsonString(json), new ExtraInfo());
    }

    @Test
    void theTypeIsClaimedUnprefixedBecauseTheLibraryOwnsIt() {
        assertTrue(Destinations.isRegistered(AlmanacDestinations.TYPE));
    }

    @Test
    void theBareWordOpensOnWhicheverSeasonIsOn() throws Exception {
        AlmanacDestinations.Almanac almanac = assertInstanceOf(AlmanacDestinations.Almanac.class, decode("\"Almanac\""));
        assertNull(almanac.getEvent());
    }

    @Test
    void contentCanNameTheSeasonToOpenOn() throws Exception {
        AlmanacDestinations.Almanac almanac = assertInstanceOf(AlmanacDestinations.Almanac.class,
                decode("{ \"Type\": \"Almanac\", \"Event\": \"Hallows_Eve\" }"));
        assertEquals("Hallows_Eve", almanac.getEvent());
    }

    @Test
    void openingWithNoPlayerBehindItDeclinesWithoutThrowing() {
        assertFalse(Destinations.open(AlmanacDestinations.ALMANAC,
                new DestinationContext(null, null, null, null, null, null)));
    }
}
