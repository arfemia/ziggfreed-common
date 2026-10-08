package com.ziggfreed.common.ui.route;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.codec.Codec;

/**
 * ONE registrable destination: the {@code Type} discriminator, the class it decodes into, its field
 * codec, the {@link DestinationHandler} that opens it, an optional {@link DestinationCheck}, and an
 * optional {@link DestinationKind} saying what kind of screen it opens.
 *
 * <p>They travel together so schema, behaviour and audit cannot drift: the id an author writes, the
 * fields they may write beside it, what happens when it is opened, and what a validator says about
 * it are all one registration in the mod that owns the screen.
 *
 * <pre>{@code
 * Destinations.register("mymod", DestinationType.of(
 *         "Mymod_Shop", ShopDestination.class, ShopDestination.CODEC, MyPages::openShop)
 *         .withCheck((destination, sourceId) -> checkStorefront(destination, sourceId))
 *         .withKind(DestinationKind.SHOP));
 * }</pre>
 *
 * @param <D> the destination class this type decodes into
 */
public final class DestinationType<D extends Destination> {

    private final String typeId;
    private final Class<D> destinationClass;
    private final Codec<D> codec;
    private final DestinationHandler<D> handler;
    @Nullable private final DestinationCheck<D> check;
    @Nullable private final DestinationKind kind;

    private DestinationType(@Nonnull String typeId, @Nonnull Class<D> destinationClass,
            @Nonnull Codec<D> codec, @Nonnull DestinationHandler<D> handler,
            @Nullable DestinationCheck<D> check, @Nullable DestinationKind kind) {
        this.typeId = typeId;
        this.destinationClass = destinationClass;
        this.codec = codec;
        this.handler = handler;
        this.check = check;
        this.kind = kind;
    }

    /**
     * A type authors write as {@code typeId}. Keep it PascalCase, and prefix a mod's own types with
     * that mod's name ({@code Mmo_Shop}), so an author can tell from the id alone which mod has to be
     * installed for the file to read.
     */
    @Nonnull
    public static <D extends Destination> DestinationType<D> of(@Nonnull String typeId,
            @Nonnull Class<D> destinationClass, @Nonnull Codec<D> codec,
            @Nonnull DestinationHandler<D> handler) {
        return new DestinationType<>(typeId, destinationClass, codec, handler, null, null);
    }

    /** A copy that also audits its own authored fields. */
    @Nonnull
    public DestinationType<D> withCheck(@Nonnull DestinationCheck<D> check) {
        return new DestinationType<>(typeId, destinationClass, codec, handler, check, kind);
    }

    /**
     * A copy that also says what kind of screen this opens, so a surface offering a way there (a
     * conversation's answer) can show it before the player presses. Presentation only: it never
     * changes what opening does.
     */
    @Nonnull
    public DestinationType<D> withKind(@Nonnull DestinationKind kind) {
        return new DestinationType<>(typeId, destinationClass, codec, handler, check, kind);
    }

    @Nonnull
    public String typeId() {
        return typeId;
    }

    @Nonnull
    public Class<D> destinationClass() {
        return destinationClass;
    }

    @Nonnull
    public Codec<D> codec() {
        return codec;
    }

    @Nonnull
    public DestinationHandler<D> handler() {
        return handler;
    }

    @Nullable
    public DestinationCheck<D> check() {
        return check;
    }

    /** What kind of screen this opens, or null when the type declared none. */
    @Nullable
    public DestinationKind kind() {
        return kind;
    }
}
