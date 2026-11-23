package com.ziggfreed.common.track.stance;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.ziggfreed.common.asset.EditorSchema;
import com.ziggfreed.common.ui.route.Destination;
import com.ziggfreed.common.ui.route.DestinationContext;
import com.ziggfreed.common.ui.route.DestinationType;
import com.ziggfreed.common.ui.route.Destinations;

/**
 * The {@code Track_Stance} destination, the library's because the stance page behind it is:
 *
 * <pre>{@code
 * "Open": { "Type": "Track_Stance", "Stance": "Your_Stance" }   one stance
 * "Open": { "Type": "Track_Stance" }                             every stance the trainer talked to holds
 * }</pre>
 *
 * <p>A trainer's conversation answer, a placement's {@code Interact} or a page's link opens it this way, on the player;
 * the placement the moment came from is where holds and releases are made, so a stance whose {@code Where} names that
 * trainer is held there. Registered at setup by {@code TrackBootstrap}, before any asset decodes.
 */
public final class StanceDestinations {

    /** The owner the registration is attributed to. */
    public static final String OWNER = "ziggfreedcommon";

    /** The {@code Type} id content writes. */
    public static final String TYPE = "Track_Stance";

    private StanceDestinations() {
    }

    /** Seed the type into the shared vocabulary, at setup, before any asset decodes. */
    public static void register() {
        Destinations.register(OWNER, DestinationType.of(TYPE, Stance.class, Stance.CODEC,
                StanceDestinations::open));
    }

    /** The destination that opens the stance {@code stanceId} (null for every one the trainer holds). */
    @Nonnull
    public static Stance of(@Nullable String stanceId) {
        Stance stance = new Stance();
        stance.stance = stanceId;
        return stance;
    }

    private static boolean open(@Nonnull Stance destination, @Nonnull DestinationContext ctx) {
        // The pressed character anchors the page when there is one, as a trainer's own page is anchored.
        return StancePages.open(destination.stance(), ctx.placementId(), ctx.store(), ctx.pageAnchor(),
                ctx.player());
    }

    /** Open the stance page. */
    public static final class Stance extends Destination {

        @Nullable protected String stance;

        public static final BuilderCodec<Stance> CODEC = BuilderCodec.builder(Stance.class, Stance::new)
                .append(new KeyedCodec<>("Stance", Codec.STRING, false), (d, v) -> d.stance = v, d -> d.stance)
                .metadata(EditorSchema.assetRef(TrackStanceAsset.class))
                .documentation("The stance to open, by id. Leave it out for every stance whose Where names the "
                        + "trainer the player is talking to (every stance, away from any trainer).").add()
                .build();

        public Stance() {
        }

        /** The stance named, or null. */
        @Nullable
        public String stance() {
            return stance == null || stance.isBlank() ? null : stance.trim();
        }
    }
}
