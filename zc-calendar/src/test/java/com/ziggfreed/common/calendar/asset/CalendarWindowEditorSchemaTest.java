package com.ziggfreed.common.calendar.asset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nonnull;

import org.junit.jupiter.api.Test;

import com.hypixel.hytale.codec.schema.SchemaContext;
import com.hypixel.hytale.codec.schema.config.ArraySchema;
import com.hypixel.hytale.codec.schema.config.BooleanSchema;
import com.hypixel.hytale.codec.schema.config.IntegerSchema;
import com.hypixel.hytale.codec.schema.config.ObjectSchema;
import com.hypixel.hytale.codec.schema.config.Schema;
import com.hypixel.hytale.codec.schema.config.StringSchema;

/**
 * What the Window tells the in-game Asset Editor: the Rule union advertises all five shapes and its
 * Type key, the shifts and the repeat leaves declare their defaults, the weekday is a closed dropdown,
 * Months is a list, the Years map knows its $Comment key, a Years entry is a shape of its own (no Type) with
 * lists of spans and of run numbers to skip, and every leaf carries a sentence.
 */
class CalendarWindowEditorSchemaTest {

    @Test
    void theRuleUnionAdvertisesEveryShape() {
        Schema rule = WindowRules.CODEC.toSchema(new SchemaContext());
        assertNotNull(rule.getAnyOf());
        assertEquals(5, rule.getAnyOf().length, "one arm per shape the codec decodes");
        assertEquals("Type", rule.getHytaleSchemaTypeField().getProperty());
        assertEquals(Set.of(WindowRules.FIXED, WindowRules.EASTER, WindowRules.WEEKDAY, WindowRules.MONTHLY,
                WindowRules.WEEKLY), Set.of(rule.getHytaleSchemaTypeField().getValues()));
    }

    @Test
    void theRepeatingShapesDeclareTheirDefaultsAndListTheirMonths() {
        ObjectSchema monthly = WindowRules.Monthly.CODEC.toSchema(new SchemaContext());
        assertEquals(Integer.valueOf(1), ((IntegerSchema) monthly.getProperties().get("Days")).getDefault());
        assertEquals(Integer.valueOf(1), ((IntegerSchema) monthly.getProperties().get("Every")).getDefault());
        assertEquals(Boolean.FALSE, ((BooleanSchema) monthly.getProperties().get("UntilNext")).getDefault());
        assertEquals(7, ((StringSchema) monthly.getProperties().get("Weekday")).getEnum().length);
        assertTrue(monthly.getProperties().get("Months") instanceof ArraySchema, "a list of month numbers");
        for (String leaf : List.of("Day", "Weekday", "Nth", "Days", "At", "Length", "UntilNext", "Every", "Anchor",
                "Months")) {
            assertNotNull(monthly.getProperties().get(leaf), leaf + " is not exported");
            assertNotNull(monthly.getProperties().get(leaf).getMarkdownDescription(), leaf + " carries no sentence");
        }
        ObjectSchema weekly = WindowRules.Weekly.CODEC.toSchema(new SchemaContext());
        assertEquals(Integer.valueOf(1), ((IntegerSchema) weekly.getProperties().get("Days")).getDefault());
        assertNotNull(weekly.getProperties().get("At"));
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

    @Test
    void aYearsEntryIsItsOwnShapeAndOffersNoTypeKey() {
        SchemaContext context = new SchemaContext();
        ObjectSchema window = CalendarEventAsset.Window.CODEC.toSchema(context);
        ObjectSchema years = objectOf(window.getProperties().get("Years"), context);
        ObjectSchema entry = objectOf((Schema) years.getAdditionalProperties(), context);
        for (String leaf : List.of("Start", "End", "Runs", "Skip")) {
            assertNotNull(entry.getProperties().get(leaf), leaf + " is not exported");
            assertNotNull(entry.getProperties().get(leaf).getMarkdownDescription(), leaf + " carries no sentence");
        }
        assertFalse(entry.getProperties().containsKey(WindowRules.TYPE_KEY),
                "a Years entry is no Rule shape, so the editor never offers it a Type");
        assertTrue(entry.getProperties().get("Runs") instanceof ArraySchema runs && runs.getItems() != null,
                "Runs is a list of spans, and declares them");
        assertTrue(entry.getProperties().get("Skip") instanceof ArraySchema skip
                && skip.getItems() instanceof IntegerSchema, "Skip is a list of run numbers");
        SchemaContext fixedContext = new SchemaContext();
        ObjectSchema fixed = WindowRules.Fixed.CODEC.toSchema(fixedContext);
        assertTrue(fixed.getProperties().get("Runs") instanceof ArraySchema, "a Fixed Rule lists spans the same way");
        ObjectSchema span = objectOf((Schema) ((ArraySchema) fixed.getProperties().get("Runs")).getItems(), fixedContext);
        for (String leaf : List.of("Start", "End")) {
            assertNotNull(span.getProperties().get(leaf), "a span's " + leaf + " is not exported");
            assertNotNull(span.getProperties().get(leaf).getMarkdownDescription(), "a span's " + leaf + " carries no sentence");
        }
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
