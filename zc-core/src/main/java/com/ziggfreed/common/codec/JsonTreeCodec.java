package com.ziggfreed.common.codec;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.bson.BsonArray;
import org.bson.BsonBoolean;
import org.bson.BsonDocument;
import org.bson.BsonDouble;
import org.bson.BsonInt64;
import org.bson.BsonNull;
import org.bson.BsonString;
import org.bson.BsonValue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.codec.schema.SchemaContext;
import com.hypixel.hytale.codec.schema.config.ArraySchema;
import com.hypixel.hytale.codec.schema.config.BooleanSchema;
import com.hypixel.hytale.codec.schema.config.NumberSchema;
import com.hypixel.hytale.codec.schema.config.ObjectSchema;
import com.hypixel.hytale.codec.schema.config.Schema;
import com.hypixel.hytale.codec.schema.config.StringSchema;
import com.hypixel.hytale.codec.util.RawJsonReader;

/**
 * Captures an authored JSON subtree VERBATIM, as a value the owning codec hands on without
 * interpreting. It is the one field type for "whatever the author wrote here is a template someone
 * else fills in later", which is exactly what a generator's child body and an enumerator filter are.
 *
 * <p>Numbers keep their authored spelling ({@code 10} stays {@code 10}, never {@code 10.0}), because
 * the captured tree is re-emitted as JSON and decoded a second time through a codec whose field may
 * be an integer. Object key ORDER is preserved for the same reason a diff is easier to read when it
 * matches the file.
 *
 * <p>Two shapes, so the in-game editor is told what to expect: {@link #object()} for a nested object
 * and {@link #array()} for a list. Both decode any JSON value; the difference is documentation, not
 * enforcement.
 *
 * <p>Reach for this ONLY where a subtree genuinely has no schema at this level. A field with a
 * known shape is a nested codec, which is what gets validation, inheritance, and editor support.
 *
 * <p>Any asset type may use it; a quest generator's {@code Child} body and its axis {@code Values}
 * are the shipped callers today.
 */
public final class JsonTreeCodec implements Codec<JsonElement> {

    private static final JsonTreeCodec OBJECT = new JsonTreeCodec(false);
    private static final JsonTreeCodec ARRAY = new JsonTreeCodec(true);

    private final boolean arrayShaped;

    private JsonTreeCodec(boolean arrayShaped) {
        this.arrayShaped = arrayShaped;
    }

    /** The instance for a field whose authored value is a nested OBJECT. */
    @Nonnull
    public static JsonTreeCodec object() {
        return OBJECT;
    }

    /** The instance for a field whose authored value is an ARRAY. */
    @Nonnull
    public static JsonTreeCodec array() {
        return ARRAY;
    }

    // ==================== decode ====================

    @Override
    public JsonElement decode(@Nonnull BsonValue bsonValue, @Nonnull ExtraInfo extraInfo) {
        return fromBson(bsonValue);
    }

    @Override
    public JsonElement decodeJson(@Nonnull RawJsonReader reader, @Nonnull ExtraInfo extraInfo) throws IOException {
        return readValue(reader);
    }

    /** Read ONE JSON value at the reader's cursor, consuming exactly that value. */
    @Nonnull
    private static JsonElement readValue(@Nonnull RawJsonReader reader) throws IOException {
        reader.consumeWhiteSpace();
        int next = reader.peek();
        switch (next) {
            case '{':
                return readObject(reader);
            case '[':
                return readArray(reader);
            case '"':
                return new JsonPrimitive(reader.readString());
            case 't':
            case 'f':
                return new JsonPrimitive(reader.readBooleanValue());
            case 'n':
                reader.readNullValue();
                return JsonNull.INSTANCE;
            default:
                return readNumber(reader);
        }
    }

    @Nonnull
    private static JsonObject readObject(@Nonnull RawJsonReader reader) throws IOException {
        JsonObject out = new JsonObject();
        reader.expect('{');
        reader.consumeWhiteSpace();
        if (reader.tryConsume('}')) {
            return out;
        }
        while (true) {
            String key = reader.readString();
            reader.consumeWhiteSpace();
            reader.expect(':');
            out.add(key, readValue(reader));
            reader.consumeWhiteSpace();
            if (reader.tryConsumeOrExpect('}', ',')) {
                return out;
            }
            reader.consumeWhiteSpace();
        }
    }

    @Nonnull
    private static JsonArray readArray(@Nonnull RawJsonReader reader) throws IOException {
        JsonArray out = new JsonArray();
        reader.expect('[');
        reader.consumeWhiteSpace();
        if (reader.tryConsume(']')) {
            return out;
        }
        while (true) {
            out.add(readValue(reader));
            reader.consumeWhiteSpace();
            if (reader.tryConsumeOrExpect(']', ',')) {
                return out;
            }
            reader.consumeWhiteSpace();
        }
    }

    /**
     * Read a number as its authored TEXT, so an integer survives the round trip as an integer. A
     * {@link BigDecimal} prints back exactly what was read, where a double would turn {@code 10}
     * into {@code 10.0} and fail the integer field it is eventually decoded into.
     */
    @Nonnull
    private static JsonPrimitive readNumber(@Nonnull RawJsonReader reader) throws IOException {
        StringBuilder text = new StringBuilder();
        while (true) {
            int c = reader.peek();
            if (c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E' || (c >= '0' && c <= '9')) {
                text.append((char) reader.read());
            } else {
                break;
            }
        }
        return new JsonPrimitive(new BigDecimal(text.toString()));
    }

    // ==================== encode ====================

    @Nonnull
    @Override
    public BsonValue encode(@Nonnull JsonElement element, ExtraInfo extraInfo) {
        return toBson(element);
    }

    @Nonnull
    private static BsonValue toBson(@Nullable JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return BsonNull.VALUE;
        }
        if (element.isJsonObject()) {
            BsonDocument document = new BsonDocument();
            for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
                document.put(entry.getKey(), toBson(entry.getValue()));
            }
            return document;
        }
        if (element.isJsonArray()) {
            BsonArray array = new BsonArray();
            for (JsonElement child : element.getAsJsonArray()) {
                array.add(toBson(child));
            }
            return array;
        }
        JsonPrimitive primitive = element.getAsJsonPrimitive();
        if (primitive.isBoolean()) {
            return BsonBoolean.valueOf(primitive.getAsBoolean());
        }
        if (primitive.isNumber()) {
            double value = primitive.getAsDouble();
            return value == Math.rint(value) && !Double.isInfinite(value)
                    ? new BsonInt64((long) value)
                    : new BsonDouble(value);
        }
        return new BsonString(primitive.getAsString());
    }

    @Nonnull
    private static JsonElement fromBson(@Nullable BsonValue value) {
        if (value == null || value.isNull()) {
            return JsonNull.INSTANCE;
        }
        if (value.isDocument()) {
            JsonObject out = new JsonObject();
            for (Map.Entry<String, BsonValue> entry : value.asDocument().entrySet()) {
                out.add(entry.getKey(), fromBson(entry.getValue()));
            }
            return out;
        }
        if (value.isArray()) {
            JsonArray out = new JsonArray();
            for (BsonValue child : value.asArray()) {
                out.add(fromBson(child));
            }
            return out;
        }
        if (value.isBoolean()) {
            return new JsonPrimitive(value.asBoolean().getValue());
        }
        if (value.isInt32()) {
            return new JsonPrimitive(value.asInt32().getValue());
        }
        if (value.isInt64()) {
            return new JsonPrimitive(value.asInt64().getValue());
        }
        if (value.isDouble()) {
            return new JsonPrimitive(value.asDouble().getValue());
        }
        if (value.isString()) {
            return new JsonPrimitive(value.asString().getValue());
        }
        return new JsonPrimitive(value.toString());
    }

    // ==================== schema ====================

    @Nonnull
    @Override
    public Schema toSchema(@Nonnull SchemaContext context) {
        if (arrayShaped) {
            // The client asset editor's schema parser dereferences every array schema's items
            // unconditionally (the engine's own ArrayCodec always sets one), so an items-less
            // ArraySchema aborts the editor's whole asset-list init. An entry here may be any
            // JSON value, so items is the anyOf of the value kinds authored in practice.
            ArraySchema schema = new ArraySchema(Schema.anyOf(
                    new ObjectSchema(), new StringSchema(), new NumberSchema(), new BooleanSchema()));
            schema.setTitle("Any JSON array");
            return schema;
        }
        ObjectSchema schema = new ObjectSchema();
        schema.setTitle("Any JSON object");
        return schema;
    }
}
