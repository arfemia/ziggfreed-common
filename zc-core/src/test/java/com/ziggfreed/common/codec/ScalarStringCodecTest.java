package com.ziggfreed.common.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;

import org.bson.BsonBoolean;
import org.bson.BsonDouble;
import org.bson.BsonInt32;
import org.bson.BsonInt64;
import org.bson.BsonNull;
import org.bson.BsonString;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;

/**
 * The bare-scalar string leaf. The property worth guarding is the SPELLING contract: whatever the
 * author wrote is what the reader on the other end parses, so {@code 5} must land as {@code "5"}
 * (never {@code "5.0"}) and a quoted value must land untouched.
 */
class ScalarStringCodecTest {

    private static String decode(String json) throws IOException {
        return ScalarStringCodec.INSTANCE.decodeJson(RawJsonReader.fromJsonString(json), new ExtraInfo());
    }

    // ==================== the three legal shapes ====================

    @Test
    void aQuotedStringDecodesUntouched() throws IOException {
        assertEquals("mastery_point", decode("\"mastery_point\""));
        assertEquals("5", decode("\"5\""), "a quoted number stays exactly what was quoted");
    }

    @Test
    void aBareIntegerKeepsItsSpelling() throws IOException {
        // "5.0" here would fail every reader that parses the text as an integer, which is the
        // whole reason the number is captured as text rather than parsed and re-printed.
        assertEquals("5", decode("5"));
        assertEquals("500", decode("500"));
        assertEquals("-3", decode("-3"));
    }

    @Test
    void aBareDecimalKeepsItsSpelling() throws IOException {
        assertEquals("2.5", decode("2.5"));
        assertEquals("0.50", decode("0.50"));
    }

    @Test
    void aBooleanDecodesToItsWord() throws IOException {
        assertEquals("true", decode("true"));
        assertEquals("false", decode("false"));
    }

    @Test
    void nullDecodesToNull() throws IOException {
        assertNull(decode("null"));
    }

    // ==================== the refusals ====================

    @Test
    void aStructureIsRefusedLoudly() {
        // A scalar slot holding an object is an authoring mistake; decoding it to some string
        // would hide the mistake inside whatever parses the parameter later.
        assertThrows(IOException.class, () -> decode("{\"a\":1}"));
        assertThrows(IOException.class, () -> decode("[1,2]"));
    }

    @Test
    void aBareWordThatIsNotANumberIsRefused() {
        // A bare word starting with a letter never reads as a scalar. Trailing junk after a valid
        // number (5x5) is the CONTAINING codec's refusal instead: this codec consumes the number
        // and the container chokes on the leftover, exactly as the engine's own number reads do.
        assertThrows(IOException.class, () -> decode("abc"));
        IOException e = assertThrows(IOException.class, () -> decode("5..5"));
        assertTrue(e.getMessage().contains("quotes"), "the refusal tells the author the fix");
    }

    // ==================== the bson lane ====================

    @Test
    void bsonScalarsDecodeToTheSameSpellings() {
        ExtraInfo info = new ExtraInfo();
        assertEquals("text", ScalarStringCodec.INSTANCE.decode(new BsonString("text"), info));
        assertEquals("5", ScalarStringCodec.INSTANCE.decode(new BsonInt32(5), info));
        assertEquals("500", ScalarStringCodec.INSTANCE.decode(new BsonInt64(500L), info));
        assertEquals("5", ScalarStringCodec.INSTANCE.decode(new BsonDouble(5.0), info),
                "an integer-valued double reads as the integer an author meant");
        assertEquals("2.5", ScalarStringCodec.INSTANCE.decode(new BsonDouble(2.5), info));
        assertEquals("true", ScalarStringCodec.INSTANCE.decode(BsonBoolean.TRUE, info));
        assertNull(ScalarStringCodec.INSTANCE.decode(BsonNull.VALUE, info));
    }

    @Test
    void encodeIsAlwaysAPlainString() {
        assertEquals(new BsonString("5"), ScalarStringCodec.INSTANCE.encode("5", new ExtraInfo()));
        assertEquals(BsonNull.VALUE, ScalarStringCodec.INSTANCE.encode(null, new ExtraInfo()));
    }
}
