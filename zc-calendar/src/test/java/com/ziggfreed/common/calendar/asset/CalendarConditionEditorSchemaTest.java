package com.ziggfreed.common.calendar.asset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.hypixel.hytale.codec.schema.SchemaContext;
import com.hypixel.hytale.codec.schema.config.ArraySchema;
import com.hypixel.hytale.codec.schema.config.BooleanSchema;
import com.hypixel.hytale.codec.schema.config.IntegerSchema;
import com.hypixel.hytale.codec.schema.config.ObjectSchema;
import com.hypixel.hytale.codec.schema.config.StringSchema;

/**
 * What the Moon and Live groups and the Herald's repeat setting tell the in-game Asset Editor, where one bad schema
 * breaks every type: each list declares its items, the effective defaults are declared, and every leaf carries a
 * sentence.
 */
class CalendarConditionEditorSchemaTest {

    @Test
    void theMoonListsItsTwoPhasesAndDefaultsToTheDefaultWorld() {
        ObjectSchema moon = CalendarEventAsset.Moon.CODEC.toSchema(new SchemaContext());
        assertTrue(moon.getProperties().get("MoonPhaseRange") instanceof ArraySchema range
                && range.getItems() instanceof IntegerSchema, "MoonPhaseRange is a list of phase numbers");
        assertEquals("", ((StringSchema) moon.getProperties().get("World")).getDefault(), "blank is the default world");
        for (String leaf : List.of("MoonPhaseRange", "World")) {
            assertNotNull(moon.getProperties().get(leaf).getMarkdownDescription(), leaf + " carries no sentence");
        }
    }

    @Test
    void theLiveListsDeclareTheirIds() {
        ObjectSchema live = CalendarEventAsset.Live.CODEC.toSchema(new SchemaContext());
        for (String leaf : List.of("AnyOf", "AllOf", "NoneOf")) {
            assertTrue(live.getProperties().get(leaf) instanceof ArraySchema list
                    && list.getItems() instanceof StringSchema, leaf + " is a list of event ids");
            assertNotNull(live.getProperties().get(leaf).getMarkdownDescription(), leaf + " carries no sentence");
        }
    }

    @Test
    void theHeraldDeclaresItsRepeatDefault() {
        ObjectSchema herald = CalendarEventAsset.Herald.CODEC.toSchema(new SchemaContext());
        assertEquals(Boolean.FALSE, ((BooleanSchema) herald.getProperties().get("OncePerDay")).getDefault(),
                "unauthored, the banner shows at every rise");
        assertNotNull(herald.getProperties().get("OncePerDay").getMarkdownDescription());
    }
}
