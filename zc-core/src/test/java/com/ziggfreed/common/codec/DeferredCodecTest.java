package com.ziggfreed.common.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

import org.bson.BsonString;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;

/**
 * {@link DeferredCodec}: delegation is verbatim, the source is untouched at CONSTRUCTION (the whole
 * point of the type - it is what lets a codec graph reference a not-yet-buildable codec or an
 * engine constant without forcing it at class-load), and the delegate is asked for FRESH at each
 * use rather than memoized (a registration-built vocabulary can still change after the first read;
 * the supplier's own side does any caching).
 */
class DeferredCodecTest {

    @Test
    void constructionDoesNotResolveTheSource() {
        AtomicInteger resolutions = new AtomicInteger();
        new DeferredCodec<String>(() -> {
            resolutions.incrementAndGet();
            return Codec.STRING;
        });
        assertEquals(0, resolutions.get(), "the source must not be touched until a value flows");
    }

    @Test
    void decodeAndEncodeDelegateVerbatim() throws IOException {
        DeferredCodec<String> codec = new DeferredCodec<>(() -> Codec.STRING);
        ExtraInfo info = new ExtraInfo();

        assertEquals("hello", codec.decode(new BsonString("hello"), info));
        assertEquals(new BsonString("hello"), codec.encode("hello", info));
        assertEquals("json", codec.decodeJson(RawJsonReader.fromJsonString("\"json\""), info));
    }

    /** Fresh each use, never memoized: a memo would pin a vocabulary from before it was complete. */
    @Test
    void theSourceIsAskedFreshAtEachUse() {
        AtomicInteger resolutions = new AtomicInteger();
        DeferredCodec<String> codec = new DeferredCodec<>(() -> {
            resolutions.incrementAndGet();
            return Codec.STRING;
        });
        ExtraInfo info = new ExtraInfo();
        codec.decode(new BsonString("one"), info);
        codec.encode("two", info);
        codec.decode(new BsonString("three"), info);
        assertEquals(3, resolutions.get());
    }

    @Test
    void delegateAnswersTheResolvedCodec() {
        DeferredCodec<String> codec = new DeferredCodec<>(() -> Codec.STRING);
        assertSame(Codec.STRING, codec.delegate());
    }
}
