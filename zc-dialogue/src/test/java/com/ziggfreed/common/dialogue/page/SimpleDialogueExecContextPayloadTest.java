package com.ziggfreed.common.dialogue.page;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ziggfreed.common.dialogue.DialoguePayloads;
import com.ziggfreed.common.dialogue.schema.NpcDialogue;

/**
 * Payload precedence, which is what keeps a mod's own conversations behaving exactly as they did
 * while still letting its gates and quest lines answer inside somebody else's conversation.
 *
 * <p>The EXPLICIT payload the opening mod packed in always wins and is never rebuilt, so nothing
 * about a mod talking to its own characters changes and no self-heal runs twice. Only a genuine miss
 * - a conversation another mod opened, carrying nothing of this shape - reaches the process-wide
 * supplier its owner registered.
 *
 * <p><b>The fixture passes null for four {@code @Nonnull} handles, and here is exactly what that
 * rests on.</b> A {@code Store}, a {@code Ref}, a {@code PlayerRef} and a {@code Player} cannot be
 * stood up in a unit JVM at all, so the alternative to a null is no test. The contract relied on is
 * narrow and stated once: {@link SimpleDialogueExecContext}'s constructor only ASSIGNS those four
 * fields - it neither validates nor dereferences them - and {@code payload(Class)} only passes them
 * straight through to whichever supplier was registered, which these suppliers ignore. The memory
 * store is the one thing that would need real handles, and {@code flags()} resolves it on first use
 * rather than at construction, so a case that never asks for a memory never asks for one.
 *
 * <p>If that contract ever changes - the constructor starts rejecting a null, or payload resolution
 * starts reading one - THIS FIXTURE is what gets a real stand-in or a harness that can build the
 * handles. The production constructor does not grow null-tolerance to keep this test green.
 *
 * <p>A fallback is also asked for at most ONCE per context, which matters because a supplier may
 * reconcile the player's saved state on the way past and a render evaluates many gated options
 * through one context.
 */
class SimpleDialogueExecContextPayloadTest {

    /** One mod's payload. */
    record ModAPayload(String value) { }

    /** Another mod's payload, so a miss is a miss on TYPE rather than on absence. */
    record ModBPayload(String value) { }

    @BeforeEach
    void freshRegistry() {
        DialoguePayloads.resetForTests();
    }

    @AfterEach
    void clearRegistry() {
        DialoguePayloads.resetForTests();
    }

    private SimpleDialogueExecContext contextWith(Object payload) {
        return new SimpleDialogueExecContext(null, null, null, null,
                payload, new NpcDialogue(), "greet", -1);
    }

    @Test
    void theExplicitPayloadWinsAndTheRegisteredSupplierIsNeverAsked() {
        AtomicInteger built = new AtomicInteger();
        ModAPayload explicit = new ModAPayload("packed in");
        DialoguePayloads.register(ModAPayload.class, "ModA", (store, ref, playerEntity) -> {
            built.incrementAndGet();
            return new ModAPayload("rebuilt");
        });

        assertSame(explicit, contextWith(explicit).payload(ModAPayload.class));
        assertEquals(0, built.get(),
                "rebuilding a payload the opener already packed in would run its side effects twice");
    }

    @Test
    void aNullPayloadFallsThroughToTheRegisteredSupplier() {
        DialoguePayloads.register(ModAPayload.class, "ModA",
                (store, ref, playerEntity) -> new ModAPayload("built for anyone"));

        ModAPayload resolved = contextWith(null).payload(ModAPayload.class);

        assertEquals(new ModAPayload("built for anyone"), resolved,
                "a conversation another mod opened carries no payload, and that is the collision case");
    }

    @Test
    void aPayloadOfAnotherModsTypeFallsThroughForTheTypeActuallyAsked() {
        DialoguePayloads.register(ModAPayload.class, "ModA",
                (store, ref, playerEntity) -> new ModAPayload("built for anyone"));

        SimpleDialogueExecContext ctx = contextWith(new ModBPayload("somebody else's"));

        assertEquals(new ModAPayload("built for anyone"), ctx.payload(ModAPayload.class));
        assertEquals(new ModBPayload("somebody else's"), ctx.payload(ModBPayload.class),
                "and the payload that IS there still answers for its own type");
    }

    @Test
    void withNothingRegisteredAMissIsStillNull() {
        assertNull(contextWith(null).payload(ModAPayload.class),
                "a mod that registered no supplier behaves exactly as it did before this seam existed");
    }

    @Test
    void aThrowingSupplierDegradesToNoPayloadRatherThanBreakingTheRender() {
        DialoguePayloads.register(ModAPayload.class, "ModA", (store, ref, playerEntity) -> {
            throw new IllegalStateException("boom");
        });

        assertNull(contextWith(null).payload(ModAPayload.class));
    }

    @Test
    void aFallbackIsBuiltOncePerContextNoMatterHowManyLinesAskForIt() {
        AtomicInteger built = new AtomicInteger();
        DialoguePayloads.register(ModAPayload.class, "ModA", (store, ref, playerEntity) -> {
            built.incrementAndGet();
            return new ModAPayload("built for anyone");
        });

        SimpleDialogueExecContext ctx = contextWith(null);
        assertEquals(new ModAPayload("built for anyone"), ctx.payload(ModAPayload.class));
        assertEquals(new ModAPayload("built for anyone"), ctx.payload(ModAPayload.class));
        assertEquals(new ModAPayload("built for anyone"), ctx.payload(ModAPayload.class));

        assertEquals(1, built.get(),
                "every gated option in a render asks this same context, and a supplier may reconcile "
                        + "saved state on the way past, so it is asked once");

        assertEquals(new ModAPayload("built for anyone"), contextWith(null).payload(ModAPayload.class));
        assertEquals(2, built.get(), "a fresh context is a fresh render, and asks again");
    }

    @Test
    void aSupplierThatAnswersWithNothingIsAlsoOnlyAskedOnce() {
        AtomicInteger asked = new AtomicInteger();
        DialoguePayloads.register(ModAPayload.class, "ModA", (store, ref, playerEntity) -> {
            asked.incrementAndGet();
            return null;
        });

        SimpleDialogueExecContext ctx = contextWith(null);
        assertNull(ctx.payload(ModAPayload.class));
        assertNull(ctx.payload(ModAPayload.class));

        assertEquals(1, asked.get(), "'nobody has one' is an answer worth remembering too");
    }

    @Test
    void theFirstSupplierForAClassHoldsItAndASecondIsRefused() {
        DialoguePayloads.register(ModAPayload.class, "ModA",
                (store, ref, playerEntity) -> new ModAPayload("first"));
        DialoguePayloads.register(ModAPayload.class, "ModB",
                (store, ref, playerEntity) -> new ModAPayload("second"));

        assertEquals(new ModAPayload("first"), contextWith(null).payload(ModAPayload.class),
                "two answers to 'what is this mod's payload' is a contradiction, not a contribution");
    }
}
