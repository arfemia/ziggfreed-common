package com.ziggfreed.common.interaction.type;

import java.util.Objects;
import java.util.function.Supplier;

import javax.annotation.Nonnull;

import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.Interaction;

/**
 * One registrable custom interaction Type: its authored {@code "Type"} name, its class, and a
 * SUPPLIER of its codec.
 *
 * <p><b>The supplier is the codec-init-safe convention, not a style choice.</b> A concrete
 * Type's {@code BuilderCodec} chains into {@code SimpleInstantInteraction.CODEC} -&gt; {@code
 * Interaction.ABSTRACT_CODEC} -&gt; {@code Interaction.CODEC}, and forcing that class-init
 * outside a live server throws: {@code Interaction}'s own static initializer transitively
 * reaches a {@code RangeValidator} whose {@code <clinit>} touches {@code HytaleLogger}, which
 * throws {@code IllegalStateException("Log manager wasn't set!")} unless the Hytale server has
 * already installed {@code HytaleLogManager} as the JVM's log manager (documented at
 * {@code additional-mods/command-interactions/src/main/java/com/ziggfreed/interactioncommands
 * /interaction/RunCommandInteraction.java:127-137}).
 *
 * <p>Holding a {@link Supplier} instead of the codec itself means a spec can be CONSTRUCTED,
 * listed, and unit-tested in a log-manager-less JVM; only {@link InteractionTypes#register}
 * ever calls {@code get()}, and only at plugin {@code setup()} on a live server. A Type
 * therefore exposes {@code static BuilderCodec<X> codec()} (a lazy holder), NEVER a {@code
 * public static final CODEC} field.
 *
 * <p>Immutable; {@link #toString()} never invokes the supplier.
 */
public final class InteractionTypeSpec {

    @Nonnull
    private final String typeName;
    @Nonnull
    private final Class<? extends Interaction> type;
    @Nonnull
    private final Supplier<? extends BuilderCodec<? extends Interaction>> codecSupplier;

    private InteractionTypeSpec(@Nonnull String typeName, @Nonnull Class<? extends Interaction> type,
            @Nonnull Supplier<? extends BuilderCodec<? extends Interaction>> codecSupplier) {
        this.typeName = typeName;
        this.type = type;
        this.codecSupplier = codecSupplier;
    }

    /**
     * @throws NullPointerException on any null argument (a construction-time programmer error,
     *                              not a runtime degradation)
     */
    @Nonnull
    public static InteractionTypeSpec of(@Nonnull String typeName, @Nonnull Class<? extends Interaction> type,
            @Nonnull Supplier<? extends BuilderCodec<? extends Interaction>> codecSupplier) {
        Objects.requireNonNull(typeName, "typeName");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(codecSupplier, "codecSupplier");
        return new InteractionTypeSpec(typeName.trim(), type, codecSupplier);
    }

    /** The authored {@code "Type"} name, trimmed. May be blank; rejected only at registration. */
    @Nonnull
    public String typeName() {
        return typeName;
    }

    /** The concrete {@link Interaction} subclass this Type decodes to. */
    @Nonnull
    public Class<? extends Interaction> type() {
        return type;
    }

    /** The deferred codec supplier. Only {@link InteractionTypes#register} may invoke it. */
    @Nonnull
    public Supplier<? extends BuilderCodec<? extends Interaction>> codecSupplier() {
        return codecSupplier;
    }

    /** {@code InteractionTypeSpec{typeName='...', type=...}}. Never invokes the supplier. */
    @Nonnull
    @Override
    public String toString() {
        return "InteractionTypeSpec{typeName='" + typeName + "', type=" + type + '}';
    }
}
