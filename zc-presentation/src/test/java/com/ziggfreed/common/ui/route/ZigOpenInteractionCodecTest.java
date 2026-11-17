package com.ziggfreed.common.ui.route;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;

import javax.annotation.Nullable;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.lookup.ACodecMapCodec;
import com.hypixel.hytale.codec.util.RawJsonReader;

/**
 * The ZigOpen Type reads its {@code Open} the way every other site authoring a destination does: the object form with
 * its fields, the bare name of a type with none, nothing when unauthored, and a read failure for a type nobody
 * registered (a pad that silently opens nothing would otherwise be found only by a player stepping on it). Tagged
 * {@code engine-items}, every method: the Type's codec chains into {@code Interaction}, whose class init needs the
 * engine's log manager.
 */
class ZigOpenInteractionCodecTest {

    /** A type with a field, standing in for the travel destination's Instance. */
    static final class Place extends Destination {

        @Nullable protected String place;

        static final BuilderCodec<Place> CODEC = BuilderCodec.builder(Place.class, Place::new)
                .append(new KeyedCodec<>("Place", Codec.STRING, false), (d, v) -> d.place = v, d -> d.place).add()
                .build();
    }

    /** A type with no fields, standing in for the travel destination's Overworld. */
    static final class Outside extends Destination {

        static final BuilderCodec<Outside> CODEC = BuilderCodec.builder(Outside.class, Outside::new).build();
    }

    @BeforeEach
    void register() {
        Destinations.clearForTests();
        Destinations.register("test", DestinationType.of("Place", Place.class, Place.CODEC, (d, ctx) -> false));
        Destinations.register("test", DestinationType.of("Outside", Outside.class, Outside.CODEC, (d, ctx) -> false));
    }

    @AfterEach
    void reset() {
        Destinations.clearForTests();
    }

    private static ZigOpenInteraction decode(String json) throws IOException {
        return ZigOpenInteraction.codec().decodeJson(RawJsonReader.fromJsonString(json), new ExtraInfo());
    }

    @Tag("engine-items")
    @Test
    void theObjectFormReadsItsTypeAndFields() throws IOException {
        ZigOpenInteraction open = decode("{ \"Open\": { \"Type\": \"Place\", \"Place\": \"Forgotten_Temple_Goblins\" } }");
        Place place = assertInstanceOf(Place.class, open.destination());
        assertEquals("Forgotten_Temple_Goblins", place.place);
    }

    @Tag("engine-items")
    @Test
    void theBareNameIsTheSameDestination() throws IOException {
        assertInstanceOf(Outside.class, decode("{ \"Open\": \"Outside\" }").destination());
        assertInstanceOf(Outside.class, decode("{ \"Open\": { \"Type\": \"Outside\" } }").destination());
    }

    @Tag("engine-items")
    @Test
    void anUnauthoredOpenIsNone() throws IOException {
        assertNull(decode("{}").destination(), "unauthored, the chain takes its Failed branch");
    }

    @Tag("engine-items")
    @Test
    void aTypeNobodyRegisteredFailsTheRead() {
        Throwable thrown = assertThrows(Exception.class, () -> decode("{ \"Open\": \"Nowhere_Registered\" }"));
        boolean unknownId = false;
        for (Throwable t = thrown; t != null && !unknownId; t = t.getCause()) {
            unknownId = t instanceof ACodecMapCodec.UnknownIdException;
        }
        assertTrue(unknownId, "the read fails on the unknown destination type itself: " + thrown);
    }
}
