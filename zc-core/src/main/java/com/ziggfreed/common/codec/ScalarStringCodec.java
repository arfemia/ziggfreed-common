package com.ziggfreed.common.codec;

import java.io.IOException;
import java.math.BigDecimal;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.bson.BsonNull;
import org.bson.BsonString;
import org.bson.BsonValue;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.codec.schema.SchemaContext;
import com.hypixel.hytale.codec.schema.config.Schema;
import com.hypixel.hytale.codec.schema.config.StringSchema;
import com.hypixel.hytale.codec.util.RawJsonReader;

/**
 * A string field an author may write WITHOUT quotes when the value is a number or a boolean:
 * {@code "Amount": 5}, {@code "Amount": "5"} and {@code "Enabled": true} all decode, each to the
 * literal spelling the author wrote ({@code "5"}, {@code "5"}, {@code "true"}).
 *
 * <p>The engine's own string codec accepts ONLY a quoted token - a bare number under it fails the
 * whole containing asset file - which makes every free-form parameter bag a place where the most
 * natural spelling of a count is illegal. This codec exists for exactly those bags: a field whose
 * VALUE is semantically text (it travels as a string, whoever registered the kind parses it), but
 * whose authors think in numbers.
 *
 * <p>Numbers keep their authored SPELLING ({@code 5} stays {@code "5"}, {@code 2.5} stays
 * {@code "2.5"}), the same rule {@link JsonTreeCodec} follows and for the same reason: the reader
 * on the other end may parse the text as an integer, and a double-round-trip {@code "5.0"} would
 * fail it. A nested object or array still refuses loudly - a scalar slot holding a structure is an
 * authoring mistake, not a spelling.
 *
 * <p>Use it for the VALUE codec of a parameter map ({@code new InheritMapCodec<>(ScalarStringCodec
 * .INSTANCE)}); a field with a known numeric meaning wants a real numeric codec instead, which is
 * what gets range validation and a number schema in the editor.
 */
public final class ScalarStringCodec implements Codec<String> {

    public static final ScalarStringCodec INSTANCE = new ScalarStringCodec();

    private ScalarStringCodec() {
    }

    // ==================== decode ====================

    @Nullable
    @Override
    public String decode(@Nonnull BsonValue value, @Nonnull ExtraInfo extraInfo) {
        if (value.isNull()) {
            return null;
        }
        if (value.isString()) {
            return value.asString().getValue();
        }
        if (value.isBoolean()) {
            return Boolean.toString(value.asBoolean().getValue());
        }
        if (value.isInt32()) {
            return Integer.toString(value.asInt32().getValue());
        }
        if (value.isInt64()) {
            return Long.toString(value.asInt64().getValue());
        }
        if (value.isDouble()) {
            double d = value.asDouble().getValue();
            return d == Math.rint(d) && !Double.isInfinite(d)
                    ? Long.toString((long) d)
                    : Double.toString(d);
        }
        return value.toString();
    }

    @Nullable
    @Override
    public String decodeJson(@Nonnull RawJsonReader reader, @Nonnull ExtraInfo extraInfo) throws IOException {
        reader.consumeWhiteSpace();
        int next = reader.peek();
        switch (next) {
            case '"':
                return reader.readString();
            case 't':
            case 'f':
                return Boolean.toString(reader.readBooleanValue());
            case 'n':
                reader.readNullValue();
                return null;
            case '{':
            case '[':
                throw new IOException("A scalar was expected here (quoted text, a bare number, or "
                        + "true/false), not a nested " + (next == '{' ? "object" : "array"));
            default:
                return readNumberText(reader);
        }
    }

    /**
     * The authored number as its own TEXT, validated as a number so a stray bare word still fails
     * the field rather than decoding as garbage.
     */
    @Nonnull
    private static String readNumberText(@Nonnull RawJsonReader reader) throws IOException {
        StringBuilder text = new StringBuilder();
        while (true) {
            int c = reader.peek();
            if (c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E' || (c >= '0' && c <= '9')) {
                text.append((char) reader.read());
            } else {
                break;
            }
        }
        if (text.isEmpty()) {
            throw new IOException("A scalar was expected here (quoted text, a bare number, or true/false)");
        }
        try {
            new BigDecimal(text.toString());
        } catch (NumberFormatException e) {
            throw new IOException("Not a number: '" + text + "' (a bare value must be a number or "
                    + "true/false; anything else needs quotes)");
        }
        return text.toString();
    }

    // ==================== encode ====================

    @Nonnull
    @Override
    public BsonValue encode(@Nullable String value, ExtraInfo extraInfo) {
        return value == null ? BsonNull.VALUE : new BsonString(value);
    }

    // ==================== schema ====================

    @Nonnull
    @Override
    public Schema toSchema(@Nonnull SchemaContext context) {
        StringSchema schema = new StringSchema();
        schema.setTitle("Text, a bare number, or true/false");
        return schema;
    }
}
