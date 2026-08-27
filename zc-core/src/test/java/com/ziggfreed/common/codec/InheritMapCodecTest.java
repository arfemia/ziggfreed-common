package com.ziggfreed.common.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.bson.BsonArray;
import org.bson.BsonDocument;
import org.bson.BsonString;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.codec.schema.SchemaContext;
import com.hypixel.hytale.codec.schema.config.ObjectSchema;
import com.hypixel.hytale.codec.schema.config.Schema;
import com.hypixel.hytale.codec.util.RawJsonReader;

/**
 * Unit tests for {@link InheritMapCodec}: authoring-comment ({@code $}-prefixed) keys are skipped
 * on both the JSON and the BSON decode path, a skipped value of ANY shape leaves the streaming JSON
 * reader in sync so the entries after it still decode, {@code Parent} merge still keys-merges
 * around a comment, and the exported schema declares the reserved editorial keys beside the value
 * codec's {@code additionalProperties} schema.
 *
 * <p>{@link Vec3} stands in as the value type: it is a {@code BuilderCodec} (so an
 * {@code InheritCodec}, exercising the deep-merge branch) with independently nullable leaves, so a
 * partial child entry proves per-field inheritance rather than wholesale replacement.
 */
class InheritMapCodecTest {

    private static final InheritMapCodec<Vec3> CODEC = new InheritMapCodec<>(Vec3.CODEC);

    private static Map<String, Vec3> decodeJson(String json) throws IOException {
        return CODEC.decodeJson(RawJsonReader.fromJsonString(json), new ExtraInfo());
    }

    private static Map<String, Vec3> decodeJson(String json, Map<String, Vec3> parent) throws IOException {
        return CODEC.decodeAndInheritJson(RawJsonReader.fromJsonString(json), parent, new ExtraInfo());
    }

    private static List<String> keys(Map<String, Vec3> map) {
        return new ArrayList<>(map.keySet());
    }

    @Test
    void stringValuedCommentKeyIsSkippedAndLaterEntriesStillDecode() throws IOException {
        Map<String, Vec3> map = decodeJson(
                "{ \"$Comment\": \"a note about this map\", \"A\": { \"X\": 1 },"
                        + " \"$TODO\": \"retune B\", \"B\": { \"Y\": 2 } }");

        assertEquals(List.of("A", "B"), keys(map));
        assertEquals(1.0, map.get("A").effectiveX());
        assertEquals(2.0, map.get("B").effectiveY());
    }

    @Test
    void objectAndArrayValuedCommentKeysAreConsumedWhole() throws IOException {
        // A nested object / array value is the case a naive skip gets wrong: the reader must walk
        // past the ENTIRE value, braces and commas included, or the following entries mis-parse.
        Map<String, Vec3> map = decodeJson(
                "{ \"$Comment\": { \"Why\": \"tuning\", \"Nested\": { \"Deep\": [1, 2, { \"K\": \"v\" }] } },"
                        + " \"A\": { \"X\": 1, \"Y\": 2 },"
                        + " \"$Notes\": [ \"one\", { \"Two\": true }, null ],"
                        + " \"B\": { \"Z\": 3 } }");

        assertEquals(List.of("A", "B"), keys(map));
        assertEquals(1.0, map.get("A").effectiveX());
        assertEquals(2.0, map.get("A").effectiveY());
        assertEquals(3.0, map.get("B").effectiveZ());
    }

    @Test
    void commentOnlyMapDecodesEmpty() throws IOException {
        Map<String, Vec3> map = decodeJson("{ \"$Comment\": { \"Why\": \"nothing authored yet\" } }");

        assertEquals(List.of(), keys(map));
    }

    @Test
    void parentMergeStillKeysMergeAroundAComment() throws IOException {
        Map<String, Vec3> parent = new LinkedHashMap<>();
        parent.put("A", Vec3.of(1.0, 2.0, 3.0));
        parent.put("B", Vec3.of(4.0, 5.0, 6.0));

        Map<String, Vec3> map = decodeJson(
                "{ \"$Comment\": \"only nudging A's Y\", \"A\": { \"Y\": 9 }, \"$TODO\": { \"Then\": \"C\" },"
                        + " \"C\": { \"X\": 7 } }",
                parent);

        assertEquals(List.of("A", "B", "C"), keys(map));
        assertFalse(map.containsKey("$Comment"));
        assertEquals(1.0, map.get("A").effectiveX(), "A.X inherits from the parent entry");
        assertEquals(9.0, map.get("A").effectiveY(), "A.Y is the child override");
        assertEquals(3.0, map.get("A").effectiveZ(), "A.Z inherits from the parent entry");
        assertEquals(4.0, map.get("B").effectiveX(), "a parent-only key is retained");
        assertEquals(7.0, map.get("C").effectiveX(), "a child-only key is added");
    }

    @Test
    void commentKeysAreSkippedOnTheBsonPath() {
        ExtraInfo info = new ExtraInfo();
        BsonDocument document = new BsonDocument();
        document.put("$Comment", new BsonString("a note about this map"));
        document.put("A", Vec3.CODEC.encode(Vec3.of(1.0, 2.0, 3.0), info));
        document.put("$Notes", new BsonArray(List.of(new BsonString("one"), new BsonString("two"))));
        document.put("B", Vec3.CODEC.encode(Vec3.of(4.0, null, null), info));

        Map<String, Vec3> map = CODEC.decode(document, info);

        assertEquals(List.of("A", "B"), keys(map));
        assertNotNull(map.get("A"));
        assertEquals(2.0, map.get("A").effectiveY());
        assertEquals(4.0, map.get("B").effectiveX());
    }

    @Test
    void bsonParentMergeStillKeysMergeAroundAComment() {
        ExtraInfo info = new ExtraInfo();
        Map<String, Vec3> parent = new LinkedHashMap<>();
        parent.put("A", Vec3.of(1.0, 2.0, 3.0));

        BsonDocument document = new BsonDocument();
        document.put("$Comment", new BsonDocument("Why", new BsonString("tuning")));
        document.put("A", Vec3.CODEC.encode(Vec3.of(null, 9.0, null), info));

        Map<String, Vec3> map = CODEC.decodeAndInherit(document, parent, info);

        assertNotNull(map);
        assertEquals(List.of("A"), keys(map));
        assertEquals(1.0, map.get("A").effectiveX(), "A.X inherits from the parent entry");
        assertEquals(9.0, map.get("A").effectiveY(), "A.Y is the child override");
    }

    @Test
    void exportedSchemaDeclaresTheReservedEditorialKeysBesideTheValueSchema() {
        // The in-game Asset Editor resolves an authored key through the exported schema. A map
        // schema carrying only additionalProperties routes a "$Comment" through the VALUE codec's
        // schema, whose shape a comment does not fit, and the property pane fails to mount; so the
        // export must declare the reserved editorial keys as (untyped) properties while
        // additionalProperties still carries the value codec's schema for every real entry.
        ObjectSchema schema = (ObjectSchema) CODEC.toSchema(new SchemaContext());

        assertNotNull(schema.getProperties(), "the reserved editorial keys must be declared");
        assertTrue(schema.getProperties().containsKey("$Comment"),
                "$Comment must appear in the exported properties");
        assertTrue(schema.getAdditionalProperties() instanceof Schema,
                "additionalProperties must still carry the value codec's schema");
    }
}
