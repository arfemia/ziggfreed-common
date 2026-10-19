package com.ziggfreed.common.usegate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * The {@code ZigLevel} item tag's grammar, the contract another mod's item file is written against:
 * {@code <Track>:<Level>}, the level after the last colon, a whole number from 1; {@code ZigLevelFeature} values
 * {@code <namespace>:<Feature_Id>}. A value that is not that shape asks nothing and is named for the audit.
 */
class ItemLevelTagsTest {

    @Test
    void aValueIsATrackAndAWholeLevel() {
        assertEquals(new ItemLevelTags.Ask("Mining", 30), ItemLevelTags.parse("Mining:30"));
        assertEquals(new ItemLevelTags.Ask("Mining", 30), ItemLevelTags.parse("  Mining : 30 "));
        assertEquals(new ItemLevelTags.Ask("yourmod:smithing", 5), ItemLevelTags.parse("yourmod:smithing:5"),
                "the level is what follows the last colon, so a namespaced track reads");
    }

    @Test
    void anythingElseIsNoValue() {
        for (String bad : new String[] {null, "", "Mining", "Mining:", ":30", "Mining:0", "Mining:-2", "Mining:3.5",
                "Mining:thirty"}) {
            assertNull(ItemLevelTags.parse(bad), "'" + bad + "' is not <Track>:<Level>");
        }
    }

    @Test
    void anItemsTagsReadWithItsSwitchesAndWhatCouldNotBeRead() {
        ItemLevelTags.Tagged tagged = ItemLevelTags.read("My_Sword", Map.of(
                "ZigLevel", new String[] {"Swords:20", "Defense:10", "Swords"},
                "ZigLevelFeature", new String[] {"yourmod:Gear_Levels", "Gear_Levels"},
                "Type", new String[] {"Weapon"}), false);

        assertEquals(List.of(new ItemLevelTags.Ask("Swords", 20), new ItemLevelTags.Ask("Defense", 10)),
                tagged.asks());
        assertEquals(1, tagged.switches().size());
        assertEquals("yourmod:feature", tagged.switches().get(0).factorId());
        assertEquals("Gear_Levels", tagged.switches().get(0).param());
        assertEquals(List.of("ZigLevel Swords", "ZigLevelFeature Gear_Levels"), tagged.malformed());
        assertEquals("ZigLevel:My_Sword", tagged.gateId());
    }

    @Test
    void aSwitchTagNoneOfWhoseValuesReadsShutsTheTagInsteadOfDroppingTheSwitch() {
        ItemLevelTags.Tagged shut = ItemLevelTags.read("My_Sword", Map.of(
                "ZigLevel", new String[] {"Swords:20"},
                "ZigLevelFeature", new String[] {"Gear_Levels", " "}), false);
        assertTrue(shut.switchUnreadable());
        assertTrue(shut.switches().isEmpty());
        assertEquals(List.of("ZigLevelFeature Gear_Levels"), shut.malformed(), "the audit names the bad value");
        assertNull(shut.gate(), "an unreadable switch is never on, so the level is never asked unconditionally");

        ItemLevelTags.Tagged behindOne = ItemLevelTags.read("My_Sword", Map.of(
                "ZigLevel", new String[] {"Swords:20"},
                "ZigLevelFeature", new String[] {"Gear_Levels", "yourmod:Gear_Levels"}), false);
        assertFalse(behindOne.switchUnreadable(), "one switch that reads is enough; the bad one is skipped");
        assertNotNull(behindOne.gate());

        assertFalse(ItemLevelTags.read("My_Sword", Map.of("ZigLevel", new String[] {"Swords:20"}), false)
                .switchUnreadable(), "no switch tag at all is no switch, never a shut one");
    }

    @Test
    void anItemWithoutTheTagIsNotTagged() {
        assertNull(ItemLevelTags.read("Plain", Map.of("Type", new String[] {"Weapon"}), false));
        assertNull(ItemLevelTags.read("Plain", null, false));
        assertTrue(ItemLevelTags.read("Empty", Map.of("ZigLevel", new String[0]), true).asks().isEmpty());
    }
}
