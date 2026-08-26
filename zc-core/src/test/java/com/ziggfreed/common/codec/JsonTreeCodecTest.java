package com.ziggfreed.common.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonElement;
import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.codec.schema.SchemaContext;
import com.hypixel.hytale.codec.schema.config.ArraySchema;
import com.hypixel.hytale.codec.util.RawJsonReader;

/**
 * The verbatim-JSON-capture codec. The two properties worth guarding are the ones that make a
 * captured subtree survive being written back out and decoded a SECOND time through a real schema:
 * a number keeps its authored spelling, and object keys keep their authored order.
 */
class JsonTreeCodecTest {

    private static JsonElement object(String json) throws IOException {
        return JsonTreeCodec.object().decodeJson(RawJsonReader.fromJsonString(json), new ExtraInfo());
    }

    private static JsonElement array(String json) throws IOException {
        return JsonTreeCodec.array().decodeJson(RawJsonReader.fromJsonString(json), new ExtraInfo());
    }

    @Test
    void anIntegerSurvivesAsAnInteger() throws IOException {
        // A double would re-emit 10 as 10.0 and fail the integer field this is eventually
        // decoded into, which is the whole reason numbers are captured as text.
        assertEquals("{\"Amount\":10}", object("{ \"Amount\": 10 }").toString());
    }

    @Test
    void aDecimalKeepsItsAuthoredSpelling() throws IOException {
        assertEquals("{\"Chance\":0.50}", object("{ \"Chance\": 0.50 }").toString());
    }

    @Test
    void aNegativeNumberKeepsItsSign() throws IOException {
        assertEquals("{\"A\":-3}", object("{ \"A\": -3 }").toString());
    }

    @Test
    void anExponentKeepsItsVALUEThoughNotItsSpelling() throws IOException {
        // What is guaranteed is the number, not the notation: BigDecimal re-prints 1e3 in its own
        // canonical form. That still decodes to 1000 in whatever field this ends up in, which is
        // the property that matters; only the integer-vs-decimal distinction is spelling-exact.
        JsonElement decoded = object("{ \"B\": 1e3 }");
        assertEquals(1000.0,
                decoded.getAsJsonObject().get("B").getAsBigDecimal().doubleValue());
    }

    @Test
    void objectKeyOrderIsPreserved() throws IOException {
        assertEquals("{\"Zebra\":1,\"Apple\":2,\"Mango\":3}",
                object("{ \"Zebra\": 1, \"Apple\": 2, \"Mango\": 3 }").toString());
    }

    @Test
    void everyJsonShapeRoundTrips() throws IOException {
        String json = "{\"S\":\"x\",\"B\":true,\"N\":null,\"A\":[1,\"two\",false],\"O\":{\"K\":7}}";
        assertEquals(json, object(json).toString());
    }

    @Test
    void anEmptyObjectAndArrayAreRead() throws IOException {
        assertEquals("{}", object("{ }").toString());
        assertEquals("[]", array("[ ]").toString());
    }

    @Test
    void anArrayOfObjectsIsCapturedWhole() throws IOException {
        assertEquals("[{\"Id\":\"a\"},{\"Id\":\"b\"}]",
                array("[ { \"Id\": \"a\" }, { \"Id\": \"b\" } ]").toString());
    }

    @Test
    void bothShapesDecodeAnyValueAndDifferOnlyInTheirSchema() throws IOException {
        // The object/array split documents intent for the in-game editor; it is not enforcement.
        assertEquals("[1,2]", object("[1, 2]").toString());
        assertTrue(List.of("Any JSON object", "Any JSON array")
                .contains(JsonTreeCodec.array().toSchema(null).getTitle()));
    }

    @Test
    void theArraySchemaAlwaysCarriesAnItemsSchema() {
        // The client asset editor's schema parser dereferences every array schema's items
        // unconditionally, so an items-less array schema aborts the editor's whole asset-list
        // init (it NPEs on the first property that exports one and no assets load at all).
        ArraySchema array = (ArraySchema) JsonTreeCodec.array().toSchema(new SchemaContext());
        assertTrue(array.getItems() != null, "array schema must declare items");
    }
}
