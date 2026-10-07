package com.ziggfreed.common.calendar.asset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nonnull;

import org.junit.jupiter.api.Test;

import com.hypixel.hytale.codec.schema.SchemaContext;
import com.hypixel.hytale.codec.schema.config.IntegerSchema;
import com.hypixel.hytale.codec.schema.config.ObjectSchema;
import com.hypixel.hytale.codec.schema.config.Schema;
import com.hypixel.hytale.codec.schema.config.StringSchema;

/**
 * What the Window tells the in-game Asset Editor: the Rule union advertises all three shapes and its
 * Type key, the shifts declare their zero defaults, the weekday is a closed dropdown, the Years map
 * knows its $Comment key, and every leaf carries a sentence.
 */
class CalendarWindowEditorSchemaTest {

    @Test
    void theRuleUnionAdvertisesEveryShape() {
        Schema rule = WindowRules.CODEC.toSchema(new SchemaContext());
        assertNotNull(rule.getAnyOf());
        assertEquals(3, rule.getAnyOf().length, "one arm per shape the codec decodes");
        assertEquals("Type", rule.getHytaleSchemaTypeField().getProperty());
        assertEquals(Set.of(WindowRules.FIXED, WindowRules.EASTER, WindowRules.WEEKDAY),
                Set.of(rule.getHytaleSchemaTypeField().getValues()));
    }

    @Test
    void theShiftsDeclareTheirZeroDefaultsAndTheWeekdayIsAClosedList() {
        ObjectSchema easter = WindowRules.Easter.CODEC.toSchema(new SchemaContext());
        assertEquals(Integer.valueOf(0), ((IntegerSchema) easter.getProperties().get("Before")).getDefault());
        assertEquals(Integer.valueOf(0), ((IntegerSchema) easter.getProperties().get("After")).getDefault());
        ObjectSchema weekday = WindowRules.Weekday.CODEC.toSchema(new SchemaContext());
        assertEquals(7, ((StringSchema) weekday.getProperties().get("Weekday")).getEnum().length);
        assertEquals(Integer.valueOf(0), ((IntegerSchema) weekday.getProperties().get("Before")).getDefault());
    }

    @Test
    void everyWindowLeafCarriesASentenceAndTheYearsMapKnowsItsCommentKey() {
        SchemaContext context = new SchemaContext();
        ObjectSchema window = CalendarEventAsset.Window.CODEC.toSchema(context);
        for (String leaf : List.of("Start", "End", "Rule", "Years")) {
            assertNotNull(window.getProperties().get(leaf), leaf + " is not exported");
            assertNotNull(window.getProperties().get(leaf).getMarkdownDescription(), leaf + " carries no sentence");
        }
        ObjectSchema years = objectOf(window.getProperties().get("Years"), context);
        assertTrue(years.getProperties().containsKey("$Comment"),
                "a $Comment inside the map is a known key, so the editor's property pane mounts");
        assertNotNull(years.getAdditionalProperties(), "any other key is a year");
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
