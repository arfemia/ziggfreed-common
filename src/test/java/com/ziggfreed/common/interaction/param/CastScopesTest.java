package com.ziggfreed.common.interaction.param;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/**
 * Pre-install characterization tests for {@link CastScopes}. Deliberately NEVER calls
 * {@link CastScopes#install()} - installing registers a {@code MetaKey} on
 * {@code Interaction.CONTEXT_META_REGISTRY}, which forces {@code Interaction}'s {@code <clinit>}
 * (the RangeValidator/HytaleLogger trap) and throws outside a live server. Every method here is
 * exercised in its documented "before install" guarded-no-op state instead; the live path is
 * smoke-covered in-game / by the consumer's own integration.
 */
class CastScopesTest {

    @Test
    void isInstalled_falseByDefault() {
        // Note: JVM-wide static state. If another test class in the same JVM run ever calls
        // install(), this would observe true. No test in this suite (or its siblings) does.
        assertFalse(CastScopes.isInstalled());
    }

    @Test
    void stash_nullContext_returnsFalse() {
        assertFalse(CastScopes.stash(null, CastScope.builder().build()));
    }

    @Test
    void stash_nullScope_returnsFalse() {
        assertFalse(CastScopes.stash(null, null));
    }

    @Test
    void read_nullContext_returnsNull() {
        assertNull(CastScopes.read(null));
    }

    @Test
    void clear_nullContext_returnsFalse() {
        assertFalse(CastScopes.clear(null));
    }

    @Test
    void decorator_nullScope_returnsNonNullNoOpConsumer() {
        var consumer = CastScopes.decorator(null);
        assertNotNull(consumer);
        // Must tolerate a null context without throwing.
        assertDoesNotThrow(() -> consumer.accept(null));
    }

    @Test
    void decorator_realScope_stillReturnsNonNullConsumerThatToleratesNullContext() {
        var consumer = CastScopes.decorator(CastScope.builder().scopeId("x").build());
        assertNotNull(consumer);
        // stash() itself guards a null ctx, so this must not throw even before install().
        assertDoesNotThrow(() -> consumer.accept(null));
    }

    @Test
    void applyVars_nullContext_returnsFalse() {
        assertFalse(CastScopes.applyVars(null, CastScope.builder().build()));
    }

    @Test
    void applyVars_nullScope_returnsFalse() {
        assertFalse(CastScopes.applyVars(null, null));
    }
}
