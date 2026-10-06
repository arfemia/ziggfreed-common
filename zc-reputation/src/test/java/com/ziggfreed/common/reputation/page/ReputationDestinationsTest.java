package com.ziggfreed.common.reputation.page;

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
import com.ziggfreed.common.ui.route.Destination;
import com.ziggfreed.common.ui.route.DestinationContext;
import com.ziggfreed.common.ui.route.Destinations;

/** The {@code Reputation} destination: every field optional, so the bare word opens it; nobody behind it declines. */
class ReputationDestinationsTest {

    @BeforeEach
    void seed() {
        Destinations.clearForTests();
        ReputationDestinations.register();
    }

    @AfterEach
    void clear() {
        Destinations.clearForTests();
    }

    private static Destination decode(String json) throws IOException {
        return Destination.CODEC.decodeJson(RawJsonReader.fromJsonString(json), new ExtraInfo());
    }

    @Test
    void theTypeIsClaimedUnprefixedBecauseTheLibraryOwnsIt() {
        assertTrue(Destinations.isRegistered(ReputationDestinations.TYPE));
    }

    @Test
    void theBareWordOpensOnTheFirstMetReputation() throws Exception {
        ReputationDestinations.Reputation bare = assertInstanceOf(ReputationDestinations.Reputation.class,
                decode("\"Reputation\""));
        assertNull(bare.getReputation());
    }

    @Test
    void contentCanNameTheReputationToOpenOn() throws Exception {
        ReputationDestinations.Reputation named = assertInstanceOf(ReputationDestinations.Reputation.class,
                decode("{ \"Type\": \"Reputation\", \"Reputation\": \"Test_Old_Jack\" }"));
        assertEquals("Test_Old_Jack", named.getReputation());
    }

    @Test
    void openingWithNoPlayerBehindItDeclinesWithoutThrowing() {
        assertFalse(Destinations.open(ReputationDestinations.REPUTATION,
                new DestinationContext(null, null, null, null, null, null)));
    }
}
