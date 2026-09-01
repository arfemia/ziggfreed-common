package com.ziggfreed.common.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;

import org.bson.BsonDocument;
import org.bson.BsonDouble;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;

/**
 * {@link Vec3i#CODEC} JSON/BSON round-trip: the whole-block {@code {X, Y, Z}} offset leaf. What
 * matters here is exactly what separates it from {@link Vec3}: integer cells refuse a fractional
 * value LOUDLY, and every axis stays independently nullable so partial authoring keeps single-axis
 * granularity.
 */
class Vec3iTest {

    private static Vec3i decode(String json) throws IOException {
        return Vec3i.CODEC.decodeJson(RawJsonReader.fromJsonString(json), new ExtraInfo());
    }

    @Test
    void decodesAllThreeAxes() throws IOException {
        Vec3i v = decode("{\"X\":3,\"Y\":-1,\"Z\":16}");
        assertEquals(3, v.getX());
        assertEquals(-1, v.getY());
        assertEquals(16, v.getZ());
    }

    @Test
    void partialAuthoringLeavesTheOtherAxesNull() throws IOException {
        Vec3i v = decode("{\"Y\":1}");
        assertNull(v.getX());
        assertEquals(1, v.getY());
        assertNull(v.getZ());
    }

    @Test
    void effectiveReadsDefaultUnauthoredAxesToZero() throws IOException {
        Vec3i v = decode("{\"Z\":-2}");
        assertEquals(0, v.effectiveX());
        assertEquals(0, v.effectiveY());
        assertEquals(-2, v.effectiveZ());
    }

    @Test
    void anEmptyGroupDecodesToAllNull() throws IOException {
        Vec3i v = decode("{}");
        assertNull(v.getX());
        assertNull(v.getY());
        assertNull(v.getZ());
    }

    /** Null axes must stay ABSENT on encode, or a partial overlay would grow authored-looking zeros. */
    @Test
    void encodeOmitsUnauthoredAxes() {
        ExtraInfo info = new ExtraInfo();
        BsonDocument doc = Vec3i.CODEC.encode(Vec3i.of(null, 4, null), info).asDocument();
        assertFalse(doc.containsKey("X"));
        assertTrue(doc.containsKey("Y"));
        assertFalse(doc.containsKey("Z"));
    }

    @Test
    void encodeThenDecodeRoundTripsEveryAxis() {
        ExtraInfo info = new ExtraInfo();
        Vec3i original = Vec3i.of(-7, 0, 12);
        Vec3i decoded = Vec3i.CODEC.decode(Vec3i.CODEC.encode(original, info), info);
        assertEquals(-7, decoded.getX());
        assertEquals(0, decoded.getY());
        assertEquals(12, decoded.getZ());
    }

    /** The reason this type exists beside Vec3: a fractional cell fails at load, never silently. */
    @Test
    void aFractionalAxisRefusesToDecode() {
        ExtraInfo info = new ExtraInfo();
        BsonDocument doc = new BsonDocument().append("X", new BsonDouble(0.5));
        assertThrows(RuntimeException.class, () -> Vec3i.CODEC.decode(doc, info));
    }
}
