package com.ziggfreed.common.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import javax.annotation.Nonnull;

import org.bson.BsonDocument;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.codec.ExtraInfo;

/**
 * What a player said about their own screen survives a save, and 2.1.0's saves load: the flat leaves are
 * read once into the groups and never written again, Show is three-state, every write says whether it
 * changed anything, and a level a later build does not know reads as no choice.
 */
class PlayerSettingsComponentTest {

    private static final String TWO_ONE = "{ \"Placements\": \"world_bars=bottom_left|activity_ledger=top_left\","
            + " \"Hidden\": \"activity_ledger\", \"HideAll\": true }";

    @Nonnull
    private static PlayerSettingsComponent decode(@Nonnull String json) {
        PlayerSettingsComponent out = new PlayerSettingsComponent();
        PlayerSettingsComponent.CODEC.decode(BsonDocument.parse(json), out, ExtraInfo.THREAD_LOCAL.get());
        return out;
    }

    @Nonnull
    private static BsonDocument encode(@Nonnull PlayerSettingsComponent settings) {
        return PlayerSettingsComponent.CODEC.encode(settings, ExtraInfo.THREAD_LOCAL.get());
    }

    @Test
    void aTwoOneSaveReadsItsFlatKeysOnce() {
        PlayerSettingsComponent old = decode(TWO_ONE);

        assertEquals("bottom_left", old.spot("World_Bars"));
        assertEquals("top_left", old.spot("activity_ledger"));
        assertEquals(Boolean.FALSE, old.shown("Activity_Ledger"), "a 2.1.0 hide is a choice to hide");
        assertNull(old.shown("World_Bars"), "a panel nobody hid is no choice, not a choice to show");
        assertTrue(old.hideAll());
        assertNull(old.level());
    }

    @Test
    void theFirstSaveWritesOnlyTheNestedGroups() {
        BsonDocument saved = encode(decode(TWO_ONE));

        assertFalse(saved.containsKey("Placements"), "2.1.0's flat leaves are read, never written");
        assertFalse(saved.containsKey("Hidden"));
        assertFalse(saved.containsKey("HideAll"));
        BsonDocument hud = saved.getDocument("Hud");
        assertEquals("bottom_left", hud.getDocument("Spots").getString("world_bars").getValue());
        assertFalse(hud.getDocument("Shown").getBoolean("activity_ledger").getValue());
        assertTrue(hud.getBoolean("HideAll").getValue());

        PlayerSettingsComponent again = decode(saved.toJson());
        assertEquals("bottom_left", again.spot("world_bars"));
        assertEquals("top_left", again.spot("activity_ledger"));
        assertEquals(Boolean.FALSE, again.shown("activity_ledger"));
        assertTrue(again.hideAll());
    }

    @Test
    void showIsThreeStateAndEveryWriteSaysWhetherItChangedAnything() {
        PlayerSettingsComponent settings = new PlayerSettingsComponent();

        assertNull(settings.shown("Quest_Tracker"), "unset until the player chooses");
        assertTrue(settings.setShown("Quest_Tracker", true));
        assertFalse(settings.setShown("QUEST_TRACKER", true), "the same choice, whatever the case");
        assertEquals(Boolean.TRUE, settings.shown("quest_tracker"));
        assertTrue(settings.setShown("Quest_Tracker", false));
        assertTrue(settings.setShown("Quest_Tracker", null), "clearing a choice is a change");
        assertFalse(settings.setShown("Quest_Tracker", null), "clearing nothing is not");
        assertNull(settings.shown("Quest_Tracker"));

        assertTrue(settings.setSpot("World_Bars", "Top_Right"));
        assertFalse(settings.setSpot("world_bars", "TOP_RIGHT"));
        assertEquals("top_right", settings.spot("World_Bars"));
        assertTrue(settings.setSpot("World_Bars", " "), "a blank pick clears it");
        assertNull(settings.spot("World_Bars"));
        assertFalse(settings.setSpot(null, "Top_Right"), "no surface, nothing kept");
        assertFalse(settings.setSpot(" ", "Top_Right"));

        assertTrue(settings.setHideAll(true));
        assertFalse(settings.setHideAll(true));
        assertTrue(settings.setHideAll(false));

        assertTrue(settings.setLevel(NotificationLevel.MILESTONES));
        assertFalse(settings.setLevel(NotificationLevel.MILESTONES));
        assertTrue(settings.setLevel(null));
        assertFalse(settings.setLevel(null));
    }

    @Test
    void aLevelRoundTripsAndAWordNobodyKnowsReadsAsNoChoice() {
        PlayerSettingsComponent settings = new PlayerSettingsComponent();
        settings.setLevel(NotificationLevel.FINISHES);

        BsonDocument saved = encode(settings);
        assertEquals("Finishes", saved.getDocument("Notifications").getString("Level").getValue());
        assertEquals(NotificationLevel.FINISHES, decode(saved.toJson()).level());
        assertNull(decode("{ \"Notifications\": { \"Level\": \"Loud\" } }").level());
    }

    @Test
    void anEmptyRecordSavesAndLoads() {
        BsonDocument saved = encode(new PlayerSettingsComponent());

        assertTrue(saved.containsKey("Hud"));
        PlayerSettingsComponent again = decode(saved.toJson());
        assertNull(again.spot("World_Bars"));
        assertNull(again.shown("World_Bars"));
        assertFalse(again.hideAll());
        assertNull(again.level());
    }

    @Test
    void aCloneIsIndependent() {
        PlayerSettingsComponent settings = new PlayerSettingsComponent();
        settings.setSpot("World_Bars", "Top_Right");
        settings.setShown("Quest_Tracker", false);
        settings.setHideAll(true);
        settings.setLevel(NotificationLevel.NONE);

        PlayerSettingsComponent copy = settings.clone();
        copy.setSpot("World_Bars", null);
        copy.setShown("Quest_Tracker", null);
        copy.setHideAll(false);
        copy.setLevel(null);

        assertEquals("top_right", settings.spot("World_Bars"));
        assertEquals(Boolean.FALSE, settings.shown("Quest_Tracker"));
        assertTrue(settings.hideAll());
        assertEquals(NotificationLevel.NONE, settings.level());
    }
}
