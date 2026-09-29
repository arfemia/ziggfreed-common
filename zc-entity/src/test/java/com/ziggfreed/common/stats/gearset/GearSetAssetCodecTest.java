package com.ziggfreed.common.stats.gearset;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.junit.jupiter.api.Test;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;
import com.hypixel.hytale.server.core.modules.entitystats.modifier.Modifier;

/**
 * The set file reads as the javadoc promises: the native item block decodes through the library's
 * own leaf with the engine's defaults, a {@code $Comment} inside the stat map is skipped, and under
 * {@code Parent} the ladder replaces wholesale while every other leaf merges. Untagged: nothing here
 * builds an engine item, and the whole point of the own modifier leaf is that this decode runs in a
 * plain unit JVM.
 */
class GearSetAssetCodecTest {

    private static final String RANGER_KIT = """
            {
              "Text": { "TitleKey": "gearset.ranger_kit.title", "FlavorKey": "gearset.ranger_kit.flavor" },
              "Members": ["Ranger_Hood", "Ranger_Coat", "ranger_hood", " ", "Ranger_Gloves", "Ranger_Boots", "Ranger_Bow"],
              "Bonuses": [
                { "Pieces": 2, "Text": { "TitleKey": "gearset.ranger_kit.tier_two" },
                  "StatModifiers": {
                    "$Comment": "Two pieces: a little more stamina.",
                    "Stamina": [ { "Amount": 10, "CalculationType": "Additive" } ]
                  } },
                { "Armor": 4, "Text": { "TitleKey": "gearset.ranger_kit.tier_armor" }, "Effect": "Ranger_Kit_Set",
                  "StatModifiers": { "Health": [ { "Amount": 20 }, { "Amount": 0.1, "CalculationType": "multiplicative", "Target": "min" } ] } },
                { "Armor": 4, "Held": 1, "Text": { "TitleKey": "gearset.ranger_kit.tier_bow" },
                  "StatModifiers": { "Mana": [ { "Amount": 15, "CalculationType": "Additive" } ] } }
              ]
            }
            """;

    @Test
    void aFullFileDecodesMembersTiersAndTheNativeModifierBlock() throws IOException {
        GearSetAsset set = decode("Ranger_Kit", RANGER_KIT, null);

        assertEquals("Ranger_Kit", set.getId());
        assertEquals("gearset.ranger_kit.title", set.titleKey());
        assertTrue(set.isEnabled(), "unauthored Enabled reads true");
        assertEquals(Set.of("ranger_hood", "ranger_coat", "ranger_gloves", "ranger_boots", "ranger_bow"),
                set.memberIds(), "members are lower-cased, a duplicate counts once and a blank is dropped");

        List<GearSetAsset.Tier> tiers = set.tiers();
        assertEquals(3, tiers.size());

        GearSetAsset.Tier two = tiers.get(0);
        assertEquals(2, two.getPieces());
        assertNull(two.getArmor());
        assertTrue(two.hasCondition());
        assertEquals("gearset.ranger_kit.tier_two", two.titleKey());
        assertNull(two.effectId());
        assertEquals(Set.of("Stamina"), two.statModifiers().keySet(), "the $Comment inside the map is not an entry");
        StatModifierSpec stamina = two.statModifiers().get("Stamina")[0];
        assertEquals(10f, stamina.amount());
        assertFalse(stamina.isMultiplicative());

        GearSetAsset.Tier armor = tiers.get(1);
        assertEquals(4, armor.getArmor());
        assertEquals("Ranger_Kit_Set", armor.effectId());
        StatModifierSpec[] health = armor.statModifiers().get("Health");
        assertEquals(2, health.length);
        assertNull(health[0].getCalculationType(), "unauthored CalculationType stays unauthored");
        assertFalse(health[0].isMultiplicative(), "and reads Additive");
        assertNull(health[0].getTarget(), "unauthored Target stays unauthored");
        assertEquals(Modifier.ModifierTarget.MAX, health[0].target(), "and reads Max");
        assertEquals(StatModifierSpec.MULTIPLICATIVE, health[1].getCalculationType(),
                "a word is matched without regard to case and stored in its canonical spelling");
        assertEquals(StatModifierSpec.MIN, health[1].getTarget());
        assertTrue(health[1].isMultiplicative());

        GearSetAsset.Tier bow = tiers.get(2);
        assertEquals(4, bow.getArmor());
        assertEquals(1, bow.getHeld());
        assertNull(bow.getUtility());
    }

    @Test
    void underParentTheLadderReplacesWholesaleAndEveryOtherLeafMerges() throws IOException {
        GearSetAsset parent = decode("Ranger_Kit", RANGER_KIT, null);
        String child = """
                {
                  "Text": { "FlavorKey": "gearset.ranger_kit_lite.flavor" },
                  "Bonuses": [
                    { "Pieces": 3, "StatModifiers": { "Stamina": [ { "Amount": 30 } ] } }
                  ]
                }
                """;

        GearSetAsset lite = decode("Ranger_Kit_Lite", child, parent);

        assertEquals("gearset.ranger_kit.title", lite.titleKey(),
                "the Text group merges leaf by leaf: the child restated FlavorKey and keeps the parent's TitleKey");
        assertEquals("gearset.ranger_kit_lite.flavor", lite.getText().getFlavorKey());
        assertEquals(parent.memberIds(), lite.memberIds(), "Members not restated are inherited");
        assertTrue(lite.isEnabled());
        assertEquals(1, lite.tiers().size(), "Bonuses replaces wholesale: the parent's three tiers are gone");
        assertEquals(3, lite.tiers().get(0).getPieces());
    }

    @Test
    void aChildRestatingNothingKeepsTheParentsLadder() throws IOException {
        GearSetAsset parent = decode("Ranger_Kit", RANGER_KIT, null);
        GearSetAsset off = decode("Ranger_Kit_Off", "{ \"Enabled\": false }", parent);

        assertFalse(off.isEnabled());
        assertEquals(3, off.tiers().size(), "an unauthored Bonuses inherits the parent's list whole");
        assertArrayEquals(parent.getMembers(), off.getMembers());
    }

    @Test
    void anUnknownCalculationWordFailsTheRead() {
        String bad = """
                { "Members": ["A", "B"], "Bonuses": [ { "Pieces": 2,
                  "StatModifiers": { "Health": [ { "Amount": 1, "CalculationType": "Additiv" } ] } } ] }
                """;
        assertThrows(Exception.class, () -> decode("Bad", bad, null),
                "a modifier that silently read as Additive would pay the wrong number, so the word is closed");
    }

    @Test
    void anUnknownTargetWordFailsTheRead() {
        String bad = """
                { "Members": ["A", "B"], "Bonuses": [ { "Pieces": 2,
                  "StatModifiers": { "Health": [ { "Amount": 1, "Target": "Mid" } ] } } ] }
                """;
        assertThrows(Exception.class, () -> decode("Bad", bad, null));
    }

    @Test
    void theJavaFactoriesFillTheSameFields() {
        GearSetAsset.Tier tier = GearSetAsset.Tier.of(2, null, null, null, null, "Look",
                Map.of("Health", new StatModifierSpec[] {StatModifierSpec.additive(4f)}));
        GearSetAsset set = GearSetAsset.of("Kit", null, null, new String[] {"A", "B"}, tier);

        assertEquals("Kit", set.getId());
        assertNull(set.titleKey());
        assertTrue(set.isEnabled());
        assertEquals(Set.of("a", "b"), set.memberIds());
        assertEquals(1, set.tiers().size());
        assertEquals("Look", set.tiers().get(0).effectId());
        assertNotNull(set.tiers().get(0).statModifiers().get("Health"));
        assertEquals(4f, set.tiers().get(0).statModifiers().get("Health")[0].toModifier().getAmount());
    }

    @Nonnull
    static GearSetAsset decode(@Nonnull String id, @Nonnull String json, @Nullable GearSetAsset parent)
            throws IOException {
        AssetExtraInfo.Data data = new AssetExtraInfo.Data(GearSetAsset.class, id,
                parent == null ? null : parent.getId());
        return GearSetAsset.CODEC.decodeAndInheritJsonAsset(RawJsonReader.fromJsonString(json), parent,
                new AssetExtraInfo<>(data));
    }
}
