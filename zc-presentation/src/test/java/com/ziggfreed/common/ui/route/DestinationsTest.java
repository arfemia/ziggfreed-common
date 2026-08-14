package com.ziggfreed.common.ui.route;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;

import javax.annotation.Nullable;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.lookup.ACodecMapCodec;
import com.hypixel.hytale.codec.util.RawJsonReader;
import com.ziggfreed.common.validation.Finding;
import com.ziggfreed.common.validation.Severity;

/**
 * The destination vocabulary's decode and dispatch contract.
 *
 * <p>Three things carry the design and each one is silent if it breaks: a type nobody registered
 * must fail the READ rather than becoming a button that does nothing, the bare-string form must be
 * the very same value the object form is, and a handler must be reached by the type that registered
 * it and by nothing else.
 *
 * <p>The fixture types stand in for a consumer mod's registrations; the seeded generic ones live
 * with the engine that opens them, a module above this one.
 */
class DestinationsTest {

    // ==================== fixtures ====================

    /** A type with fields, standing in for a consumer's parameterized destination. */
    static final class Board extends Destination {

        @Nullable protected String board;

        static final BuilderCodec<Board> CODEC = BuilderCodec.builder(Board.class, Board::new)
                .append(new KeyedCodec<>("Board", Codec.STRING, false),
                        (b, v) -> b.board = v, b -> b.board).add()
                .build();
    }

    /** A type with no fields at all, which is what the bare-string form exists for. */
    static final class Hub extends Destination {

        static final BuilderCodec<Hub> CODEC = BuilderCodec.builder(Hub.class, Hub::new).build();
    }

    private static Destination decode(String json) throws IOException {
        return Destination.CODEC.decodeJson(RawJsonReader.fromJsonString(json), new ExtraInfo());
    }

    /**
     * A context carrying no live handles. Which handler is reached, and what a failure costs, are
     * decided before any of them is read, so the dispatch contract is provable without a server; a
     * handler that really opens a page is smoke territory, like every other engine-touching path.
     */
    private static DestinationContext noHandles() {
        return new DestinationContext(null, null, null, null, null, null, null, null);
    }

    @BeforeEach
    @AfterEach
    void reset() {
        Destinations.clearForTests();
    }

    // ==================== decode ====================

    @Test
    void aRegisteredTypeDecodesIntoItsOwnFields() throws Exception {
        Destinations.register("yourmod", DestinationType.of("Yourmod_Board", Board.class, Board.CODEC,
                (destination, ctx) -> true));

        Board board = assertInstanceOf(Board.class,
                decode("{ \"Type\": \"Yourmod_Board\", \"Board\": \"daily\" }"));
        assertEquals("daily", board.board);
    }

    @Test
    void aBareStringIsTheSameValueAsTheObjectForm() throws Exception {
        Destinations.register("yourmod", DestinationType.of("Hub", Hub.class, Hub.CODEC,
                (destination, ctx) -> true));

        assertInstanceOf(Hub.class, decode("\"Hub\""),
                "one word is how a type with no fields is authored, and it must mean the object form");
        assertInstanceOf(Hub.class, decode("{ \"Type\": \"Hub\" }"));
    }

    @Test
    void anUnknownTypeFailsTheRead() {
        Destinations.register("yourmod", DestinationType.of("Hub", Hub.class, Hub.CODEC,
                (destination, ctx) -> true));

        assertThrows(ACodecMapCodec.UnknownIdException.class,
                () -> decode("{ \"Type\": \"Nobody_Registered_This\" }"),
                "a destination nothing can open must be a loud read failure, never a dead button");
        assertThrows(ACodecMapCodec.UnknownIdException.class,
                () -> decode("\"Nobody_Registered_This\""),
                "and the terse spelling must fail exactly as loudly as the object one");
    }

    @Test
    void aMisCasedTypeIsUnknownToTheRead() {
        Destinations.register("yourmod", DestinationType.of("Hub", Hub.class, Hub.CODEC,
                (destination, ctx) -> true));

        assertThrows(ACodecMapCodec.UnknownIdException.class, () -> decode("\"hub\""),
                "an authored Type is spelled exactly as registered, so a near-miss is caught at load");
        assertTrue(Destinations.isRegistered("hub"),
                "while the bookkeeping side stays case-insensitive, so an admin read still finds it");
    }

    @Test
    void aTypeRegisteredLaterIsStillReadable() throws Exception {
        Destinations.register("yourmod", DestinationType.of("Hub", Hub.class, Hub.CODEC,
                (destination, ctx) -> true));
        assertInstanceOf(Hub.class, decode("\"Hub\""));

        Destinations.register("othermod", DestinationType.of("Yourmod_Board", Board.class, Board.CODEC,
                (destination, ctx) -> true));

        assertInstanceOf(Board.class, decode("{ \"Type\": \"Yourmod_Board\" }"),
                "the vocabulary re-assembles, so a late registration still takes effect");
    }

    // ==================== dispatch ====================

    @Test
    void openReachesTheHandlerOfTheTypeThatRegisteredIt() {
        boolean[] opened = {false};
        Destinations.register("yourmod", DestinationType.of("Hub", Hub.class, Hub.CODEC,
                (destination, ctx) -> {
                    opened[0] = true;
                    return true;
                }));

        assertTrue(Destinations.open(new Hub(), noHandles()));
        assertTrue(opened[0]);
    }

    @Test
    void openingAnUnregisteredDestinationDoesNothingRatherThanThrowing() {
        assertFalse(Destinations.open(new Hub(), noHandles()),
                "nothing registered means no screen, and the caller keeps its own response");
        assertFalse(Destinations.open(null, noHandles()));
    }

    @Test
    void aThrowingHandlerCostsOnlyItsOwnOpen() {
        Destinations.register("yourmod", DestinationType.of("Hub", Hub.class, Hub.CODEC,
                (destination, ctx) -> {
                    throw new IllegalStateException("page manager refused");
                }));

        assertFalse(Destinations.open(new Hub(), noHandles()));
        assertEquals(1, Destinations.info().get("hub").failures(),
                "and the failure is counted against the owner that registered it");
    }

    // ==================== the audit hook ====================

    @Test
    void aTypesOwnCheckReportsItsOwnFindings() {
        Destinations.register("yourmod", DestinationType.of("Yourmod_Board", Board.class, Board.CODEC,
                        (destination, ctx) -> true)
                .withCheck((destination, sourceId) -> destination.board == null
                        ? List.of(Finding.error("yourmod", "NO_BOARD", "names no board", sourceId))
                        : List.of()));

        List<Finding> issues = Destinations.validate(new Board(), "mmo_hub.Interact");
        assertEquals(1, issues.size());
        assertEquals("NO_BOARD", issues.get(0).code());
        assertEquals(Severity.ERROR, issues.get(0).severity());
        assertEquals("mmo_hub.Interact", issues.get(0).sourceId());
    }

    @Test
    void aTypeThatRegisteredNoCheckReportsNothing() {
        Destinations.register("yourmod", DestinationType.of("Hub", Hub.class, Hub.CODEC,
                (destination, ctx) -> true));

        assertTrue(Destinations.validate(new Hub(), "somewhere").isEmpty());
        assertTrue(Destinations.validate(null, "somewhere").isEmpty());
    }

    // ==================== reads ====================

    @Test
    void theRegistrationIsReadableBackAsItWasSpelled() {
        Destinations.register("yourmod", DestinationType.of("Yourmod_Board", Board.class, Board.CODEC,
                (destination, ctx) -> true));

        assertEquals(List.of("Yourmod_Board"), Destinations.registeredTypes(),
                "a pick list offers the spelling an author must write, not the normalized key");
        assertEquals("Yourmod_Board", Destinations.typeIdOf(new Board()));
        assertNull(Destinations.typeIdOf(new Hub()));
        assertNotNull(Destinations.info().get("yourmod_board"));
        assertEquals("yourmod", Destinations.info().get("yourmod_board").owner());
    }
}
