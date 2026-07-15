package com.ziggfreed.common.cast.step;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.EnumMap;
import java.util.Map;

import javax.annotation.Nonnull;

import org.junit.jupiter.api.Test;

/**
 * Characterization tests for the generic {@link StepRegistry} mechanism: register / get, ships-empty,
 * last-write-wins, null-key safety, and the protected backing-map (EnumMap) construction path.
 */
class StepRegistryTest {

    private enum Kind { ALPHA, BETA }

    @Test
    void shipsEmptyAndGetReturnsNullForUnregistered() {
        StepRegistry<String, Object, Object, String> reg = new StepRegistry<>();
        assertNull(reg.get("ANY"), "an empty registry returns null for any key");
    }

    @Test
    void registerThenGetReturnsTheHandler() {
        StepRegistry<String, Object, Object, String> reg = new StepRegistry<>();
        StepHandler<Object, Object, String> h = (ctx, step) -> "ran";
        reg.register("A", h);
        assertSame(h, reg.get("A"));
        assertNull(reg.get("B"), "an unregistered key is still null");
    }

    @Test
    void registerIsLastWriteWins() {
        StepRegistry<String, Object, Object, String> reg = new StepRegistry<>();
        StepHandler<Object, Object, String> first = (ctx, step) -> "first";
        StepHandler<Object, Object, String> second = (ctx, step) -> "second";
        reg.register("A", first);
        reg.register("A", second);
        assertSame(second, reg.get("A"), "the second registration replaces the first");
    }

    @Test
    void getNullKeyIsNullNeverThrows() {
        StepRegistry<String, Object, Object, String> reg = new StepRegistry<>();
        assertNull(reg.get(null), "a null key returns null (no NPE on the concurrent-map backing)");
    }

    @Test
    void enumBackedSubclassConstructionWorks() {
        // A consumer keying by an enum can back the registry with an EnumMap via the protected ctor.
        EnumBackedRegistry reg = new EnumBackedRegistry();
        StepHandler<Object, Object, String> h = (ctx, step) -> "alpha";
        reg.register(Kind.ALPHA, h);
        assertSame(h, reg.get(Kind.ALPHA));
        assertNull(reg.get(Kind.BETA));
        assertNull(reg.get(null), "null key still null on an EnumMap backing");
    }

    /** A subclass that backs the generic registry with an {@link EnumMap}, exercising the protected ctor. */
    private static final class EnumBackedRegistry extends StepRegistry<Kind, Object, Object, String> {
        EnumBackedRegistry() {
            super(backing());
        }

        @Nonnull
        private static Map<Kind, StepHandler<Object, Object, String>> backing() {
            return new EnumMap<>(Kind.class);
        }
    }
}
