package com.ziggfreed.common.interaction.type;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.Interaction;

/**
 * {@link InteractionTypes} must reject a null/blank argument BEFORE it ever touches {@code
 * Interaction.CODEC} (which forces a clinit chain that throws outside a live server - see the
 * package {@code CLAUDE.md}). No test here may pass a non-null {@code PluginBase}, or the clinit
 * trap fires and this test crashes the unit JVM instead of failing an assertion.
 */
class InteractionTypesTest {

    private static InteractionTypeSpec specWithGuardedSupplier(AtomicBoolean invoked) {
        return InteractionTypeSpec.of("MyType", Interaction.class, () -> {
            invoked.set(true);
            throw new AssertionError("codec supplier must never be invoked when plugin is null");
        });
    }

    @Test
    void registerReturnsFalseOnNullPlugin() {
        AtomicBoolean invoked = new AtomicBoolean(false);
        InteractionTypeSpec spec = specWithGuardedSupplier(invoked);

        assertFalse(InteractionTypes.register(null, spec));
        assertFalse(invoked.get(), "codec supplier must not be invoked");
    }

    @Test
    void registerReturnsFalseOnNullSpec() {
        assertFalse(InteractionTypes.register(null, (InteractionTypeSpec) null));
    }

    @Test
    void registerReturnsFalseOnBlankTypeName() {
        AtomicBoolean invoked = new AtomicBoolean(false);
        InteractionTypeSpec spec = InteractionTypeSpec.of("   ", Interaction.class, () -> {
            invoked.set(true);
            throw new AssertionError("codec supplier must never be invoked for a blank type name");
        });

        assertFalse(InteractionTypes.register(null, spec));
        assertFalse(invoked.get());
    }

    @Test
    void registerAllReturnsZeroOnNullPlugin() {
        AtomicBoolean invoked = new AtomicBoolean(false);
        InteractionTypeSpec spec = specWithGuardedSupplier(invoked);

        assertEquals(0, InteractionTypes.registerAll(null, List.of(spec)));
        assertFalse(invoked.get());
    }

    @Test
    void registerAllReturnsZeroOnNullSpecList() {
        assertEquals(0, InteractionTypes.registerAll(null, null));
    }

    @Test
    void directFormReturnsFalseOnNullPlugin() {
        BuilderCodec<? extends Interaction> codec = null;
        assertFalse(InteractionTypes.register(null, "MyType", Interaction.class, codec));
    }

    @Test
    void directFormReturnsFalseOnNullTypeNameOrTypeOrCodec() {
        assertFalse(InteractionTypes.register(null, null, Interaction.class, null));
        assertFalse(InteractionTypes.register(null, "MyType", null, null));
        assertFalse(InteractionTypes.register(null, "   ", Interaction.class, null));
    }
}
