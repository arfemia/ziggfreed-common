package com.ziggfreed.common.stats.gearset;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import javax.annotation.Nonnull;

import org.junit.jupiter.api.Test;

import com.hypixel.hytale.codec.schema.SchemaContext;
import com.hypixel.hytale.codec.schema.config.ArraySchema;
import com.hypixel.hytale.codec.schema.config.BooleanSchema;
import com.hypixel.hytale.codec.schema.config.ObjectSchema;
import com.hypixel.hytale.codec.schema.config.Schema;
import com.hypixel.hytale.codec.schema.config.StringSchema;

/**
 * What the exported schema says to the in-game Asset Editor: every array leaf says what it holds,
 * the two modifier words are closed dropdowns with the engine's defaults, {@code Enabled} declares
 * its effective unauthored value, and a {@code $Comment} is a known key inside the stat map.
 */
class GearSetEditorSchemaTest {

    @Test
    void theArrayLeavesCarryItemsAndEnabledDeclaresTrue() {
        SchemaContext context = new SchemaContext();
        ObjectSchema set = GearSetAsset.CODEC.toSchema(context);
        Map<String, Schema> leaves = set.getProperties();

        ArraySchema members = (ArraySchema) leaves.get("Members");
        assertNotNull(members.getItems(), "Members says it holds strings");
        ArraySchema bonuses = (ArraySchema) leaves.get("Bonuses");
        assertNotNull(bonuses.getItems(), "Bonuses says it holds tiers");

        BooleanSchema enabled = (BooleanSchema) leaves.get("Enabled");
        assertEquals(Boolean.TRUE, enabled.getDefault(),
                "unauthored Enabled is true, and the exported schema must say so or the editor renders an "
                        + "unchecked box that lies about the effective value");
        for (String leaf : new String[] {"Text", "Enabled", "Members", "Bonuses"}) {
            assertNotNull(leaves.get(leaf).getMarkdownDescription(), leaf + " carries no sentence");
        }
    }

    @Test
    void theModifierWordsAreClosedDropdownsWithTheEnginesDefaults() {
        ObjectSchema spec = StatModifierSpec.CODEC.toSchema(new SchemaContext());

        StringSchema calculation = (StringSchema) spec.getProperties().get("CalculationType");
        assertArrayEquals(new String[] {"Additive", "Multiplicative"}, calculation.getEnum());
        assertEquals("Additive", calculation.getDefault());
        assertNotNull(calculation.getMarkdownEnumDescriptions());

        StringSchema target = (StringSchema) spec.getProperties().get("Target");
        assertArrayEquals(new String[] {"Max", "Min"}, target.getEnum());
        assertEquals("Max", target.getDefault());
    }

    @Test
    void aTiersStatMapDeclaresTheCommentKeyAndItsFourMinimums() {
        SchemaContext context = new SchemaContext();
        ObjectSchema tier = GearSetAsset.Tier.CODEC.toSchema(context);
        Map<String, Schema> leaves = tier.getProperties();

        assertTrue(leaves.keySet().containsAll(List.of("Pieces", "Armor", "Held", "Utility", "Text",
                "Effect", "StatModifiers")), leaves.keySet().toString());
        ObjectSchema stats = objectOf(leaves.get("StatModifiers"), context);
        assertTrue(stats.getProperties().containsKey("$Comment"),
                "a $Comment inside the map is a known key, so the editor's property pane mounts");
        assertNotNull(stats.getAdditionalProperties(), "any other key is a stat channel");
    }

    /** The object a leaf describes: itself, or the definition it references (unwrapping a nullable union). */
    @Nonnull
    private static ObjectSchema objectOf(@Nonnull Schema leaf, @Nonnull SchemaContext context) {
        Schema candidate = leaf.getAnyOf() == null ? leaf : leaf.getAnyOf()[0];
        if (candidate instanceof ObjectSchema object) {
            return object;
        }
        String ref = candidate.getRef();
        assertNotNull(ref, "the leaf is neither an object nor a reference");
        for (Map.Entry<String, Schema> entry : context.getDefinitions().entrySet()) {
            if (ref.endsWith(entry.getKey()) && entry.getValue() instanceof ObjectSchema object) {
                return object;
            }
        }
        throw new AssertionError("no definition behind " + ref + " in " + context.getDefinitions().keySet());
    }
}
