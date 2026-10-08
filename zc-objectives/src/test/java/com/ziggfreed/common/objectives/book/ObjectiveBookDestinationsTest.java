package com.ziggfreed.common.objectives.book;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import com.ziggfreed.common.ui.route.Destinations;

/**
 * The book's two screens in the shared vocabulary, unprefixed because the library owns them; each takes an
 * optional {@code Select}, the row the book opens on.
 */
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

    @Test
    void aBareScreenSelectsNothing() throws IOException {
        assertNull(((ObjectiveBookDestinations.QuestLog) decode("\"Quest_Log\"")).getSelect());
        assertNull(((ObjectiveBookDestinations.Achievements) decode("\"Achievements\"")).getSelect());
        assertNull(ObjectiveBookDestinations.QUEST_LOG.getSelect(), "the menu's tab opens on no row");
        assertNull(ObjectiveBookDestinations.ACHIEVEMENTS.getSelect());
    }

    @Test
    void selectNamesTheRowTheBookOpensOn() throws IOException {
        ObjectiveBookDestinations.QuestLog quest = assertInstanceOf(ObjectiveBookDestinations.QuestLog.class,
                decode("{ \"Type\": \"Quest_Log\", \"Select\": \"the_lantern\" }"));
        assertEquals("the_lantern", quest.getSelect());
        ObjectiveBookDestinations.Achievements achievement = assertInstanceOf(
                ObjectiveBookDestinations.Achievements.class,
                decode("{ \"Type\": \"Achievements\", \"Select\": \" Ghoul_Breaker_2026 \" }"));
        assertEquals("Ghoul_Breaker_2026", achievement.getSelect(), "trimmed, its case kept");
        assertNull(((ObjectiveBookDestinations.QuestLog) decode("{ \"Type\": \"Quest_Log\", \"Select\": \" \" }"))
                .getSelect(), "a blank selection is none");
    }

    @Test
    void achievementsMayNameACategoryAndASubcategoryToOpenOn() throws IOException {
        ObjectiveBookDestinations.Achievements focused = assertInstanceOf(ObjectiveBookDestinations.Achievements.class,
                decode("{ \"Type\": \"Achievements\", \"Category\": \" Seasons \", \"Subcategory\": \"Hallows_Eve\" }"));
        assertEquals("seasons", focused.getCategory(), "trimmed and lower-cased, as the book files a category");
        assertEquals("hallows_eve", focused.getSubcategory());
        assertNull(focused.getSelect());

        ObjectiveBookDestinations.Achievements bare = (ObjectiveBookDestinations.Achievements) decode("\"Achievements\"");
        assertNull(bare.getCategory(), "the bare word opens the book as it always has");
        assertNull(bare.getSubcategory());
        assertNull(((ObjectiveBookDestinations.Achievements) decode(
                "{ \"Type\": \"Achievements\", \"Category\": \" \" }")).getCategory(), "a blank category is none");

        ObjectiveBookDestinations.Achievements java = ObjectiveBookDestinations.Achievements.focused("Seasons", "Winter");
        assertEquals("seasons", java.getCategory());
        assertEquals("winter", java.getSubcategory());
    }

    @Test
    void javaBuildsTheSameValues() {
        assertEquals("the_lantern", ObjectiveBookDestinations.QuestLog.of("the_lantern").getSelect());
        assertEquals("a1", ObjectiveBookDestinations.Achievements.of("a1").getSelect());
        assertNull(ObjectiveBookDestinations.Achievements.of(null).getSelect());
    }
}
