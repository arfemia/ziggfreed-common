package com.ziggfreed.common.interaction.type;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;

import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.Interaction;

/**
 * {@link InteractionTypeSpec} is a pure value holder: it must never invoke its codec supplier,
 * not even from {@code toString()}. A class literal like {@code Interaction.class} is safe to
 * use in these tests (a Java class literal only LOADS the class, it does not run {@code
 * <clinit>} - initialization is deferred to first active use, which this test never triggers).
 */
class InteractionTypeSpecTest {

    private static Supplier<BuilderCodec<? extends Interaction>> throwingSupplier() {
        return () -> {
            throw new AssertionError("codec supplier must never be invoked by InteractionTypeSpec itself");
        };
    }

    @Test
    void ofStoresFieldsAndTrimsTypeName() {
        Supplier<BuilderCodec<? extends Interaction>> supplier = throwingSupplier();
        InteractionTypeSpec spec = InteractionTypeSpec.of("  MyType  ", Interaction.class, supplier);

        assertEquals("MyType", spec.typeName());
        assertEquals(Interaction.class, spec.type());
        assertNotNull(spec.codecSupplier());
    }

    @Test
    void toStringNeverInvokesTheSupplier() {
        AtomicBoolean invoked = new AtomicBoolean(false);
        Supplier<BuilderCodec<? extends Interaction>> supplier = () -> {
            invoked.set(true);
            throw new AssertionError("should never run");
        };
        InteractionTypeSpec spec = InteractionTypeSpec.of("MyType", Interaction.class, supplier);

        String rendered = spec.toString();

        assertFalse(invoked.get(), "toString must not invoke the codec supplier");
        assertNotNull(rendered);
    }

    @Test
    void ofRejectsNullArguments() {
        Supplier<BuilderCodec<? extends Interaction>> supplier = throwingSupplier();

        assertThrows(NullPointerException.class, () -> InteractionTypeSpec.of(null, Interaction.class, supplier));
        assertThrows(NullPointerException.class, () -> InteractionTypeSpec.of("MyType", null, supplier));
        assertThrows(NullPointerException.class, () -> InteractionTypeSpec.of("MyType", Interaction.class, null));
    }
}
