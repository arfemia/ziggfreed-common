package com.ziggfreed.common.interaction.type;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

/**
 * {@link InteractionOutcome} degrades to a no-op on a null context, and {@link
 * InteractionOutcome#guard} must always run its body exactly once, resolve the state from the
 * result, and never let a thrown {@code Throwable} propagate to the caller.
 */
class InteractionOutcomeTest {

    @Test
    void finishedSkipFailedAreNoOpsOnNullContext() {
        InteractionOutcome.finished(null);
        InteractionOutcome.skip(null);
        InteractionOutcome.failed(null);
    }

    @Test
    void guardReturnsTrueWhenBodySucceeds() {
        boolean result = InteractionOutcome.guard(null, "label", () -> true);
        assertTrue(result);
    }

    @Test
    void guardReturnsFalseWhenBodyFails() {
        boolean result = InteractionOutcome.guard(null, "label", () -> false);
        assertFalse(result);
    }

    @Test
    void guardReturnsFalseAndDoesNotPropagateWhenBodyThrows() {
        boolean result = InteractionOutcome.guard(null, "label", () -> {
            throw new IllegalStateException("boom");
        });
        assertFalse(result);
    }

    @Test
    void guardRunsTheBodyExactlyOnce() {
        AtomicInteger calls = new AtomicInteger(0);
        InteractionOutcome.guard(null, "label", () -> {
            calls.incrementAndGet();
            return true;
        });
        assertEquals(1, calls.get());

        AtomicInteger throwingCalls = new AtomicInteger(0);
        InteractionOutcome.guard(null, "label", () -> {
            throwingCalls.incrementAndGet();
            throw new RuntimeException("boom");
        });
        assertEquals(1, throwingCalls.get());
    }
}
