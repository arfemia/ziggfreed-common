package com.ziggfreed.common.objectives.title.page;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.ziggfreed.common.ui.route.Destination;
import com.ziggfreed.common.ui.route.DestinationContext;
import com.ziggfreed.common.ui.route.DestinationKind;
import com.ziggfreed.common.ui.route.DestinationType;
import com.ziggfreed.common.ui.route.Destinations;

/**
 * The {@code Titles} destination, unprefixed because the library owns the title picker behind it:
 *
 * <pre>{@code
 * "Open": "Titles"                                       the title picker
 * }</pre>
 *
 * <p>A consumer's menu tile, a conversation line and a placement's press-F all open the picker through this one
 * value, on the player's own ref. Authored, it carries no fields, so its Back goes where the consumer's
 * {@link TitlePickerDeps} says (the Settings tab by default). From Java, {@link #titles(Destination)} builds one whose
 * Back opens a destination of the caller's choosing instead, for that open alone (a hub tile's Back to the hub).
 * Registered at setup by {@code TitleBootstrap}, before any asset decodes.
 */
public final class TitleDestinations {

    /** The owner every registration here is attributed to. */
    public static final String OWNER = "ziggfreedcommon";

    /** The {@code Type} id content writes. */
    public static final String TYPE = "Titles";

    /** The bare destination: the picker, its Back the consumer's. */
    public static final Titles TITLES = new Titles();

    private TitleDestinations() {
    }

    /** Seed the type into the shared vocabulary, at setup, before any asset decodes. */
    public static void register() {
        Destinations.register(OWNER, DestinationType.of(TYPE, Titles.class, Titles.CODEC, TitleDestinations::open)
                .withKind(DestinationKind.TROPHY));
    }

    /** The picker whose Back opens {@code back}; null is the bare {@link #TITLES}. */
    @Nonnull
    public static Titles titles(@Nullable Destination back) {
        if (back == null) {
            return TITLES;
        }
        Titles d = new Titles();
        d.back = back;
        return d;
    }

    private static boolean open(@Nonnull Titles destination, @Nonnull DestinationContext ctx) {
        return TitlePickerPages.open(ctx.store(), ctx.playerReference(), ctx.player(), destination.back());
    }

    /** Open the title picker. */
    public static final class Titles extends Destination {

        /** No authored fields: Back is Java's alone, so a file can never send it somewhere unregistered. */
        public static final BuilderCodec<Titles> CODEC = BuilderCodec.builder(Titles.class, Titles::new).build();

        @Nullable private Destination back;

        public Titles() {
        }

        /** Where Back goes for this open, or null for the consumer's {@link TitlePickerDeps}. */
        @Nullable
        public Destination back() {
            return back;
        }
    }
}
