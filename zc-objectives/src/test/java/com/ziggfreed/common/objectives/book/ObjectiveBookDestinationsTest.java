package com.ziggfreed.common.objectives.book;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;
import com.ziggfreed.common.ui.route.Destination;
import com.ziggfreed.common.ui.route.Destinations;

/** The book's two screens in the shared vocabulary, unprefixed because the library owns them. */
class ObjectiveBookDestinationsTest {

    @BeforeEach
    void seed() {
        Destinations.clearForTests();
        ObjectiveBookDestinations.register();
    }

    @AfterEach
    void clear() {
        Destinations.clearForTests();
    }

    private static Destination decode(String json) throws IOException {
        return Destination.CODEC.decodeJson(RawJsonReader.fromJsonString(json), new ExtraInfo());
    }

    @Test
    void bothTypesAreClaimed() {
        assertTrue(Destinations.isRegistered(ObjectiveBookDestinations.QUEST_LOG_TYPE));
        assertTrue(Destinations.isRegistered(ObjectiveBookDestinations.ACHIEVEMENTS_TYPE));
    }

    @Test
    void theBareWordsDecode() throws IOException {
        assertInstanceOf(ObjectiveBookDestinations.QuestLog.class, decode("\"Quest_Log\""));
        assertInstanceOf(ObjectiveBookDestinations.Achievements.class, decode("{ \"Type\": \"Achievements\" }"));
    }
}
