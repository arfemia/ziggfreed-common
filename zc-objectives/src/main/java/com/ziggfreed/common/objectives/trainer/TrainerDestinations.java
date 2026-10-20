package com.ziggfreed.common.objectives.trainer;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.ziggfreed.common.ui.route.Destination;
import com.ziggfreed.common.ui.route.DestinationContext;
import com.ziggfreed.common.ui.route.DestinationType;
import com.ziggfreed.common.ui.route.Destinations;

/**
 * The {@code Trainer} destination, unprefixed because the library owns the trainer page behind it:
 *
 * <pre>{@code
 * "Open": "Trainer"                                           the trainer the player is talking to
 * "Open": { "Type": "Trainer", "Placement": "Zc_Trainer_Example" }   a named trainer
 * }</pre>
 *
 * <p>A trainer's conversation gives its Train answer this way, a placement's {@code Interact} can open the page
 * straight from press F, and a page's link can point at one. Unnamed, the trainer is the placement the moment came
 * from, else the first trainer placement standing the character the conversation is with ({@link Trainers#resolve}).
 * Registered at setup by {@code TrainerBootstrap}, before any asset decodes.
 */
public final class TrainerDestinations {

    /** The owner every registration here is attributed to. */
    public static final String OWNER = "ziggfreedcommon";

    /** The {@code Type} id content writes. */
    public static final String TYPE = "Trainer";

    private TrainerDestinations() {
    }

    /** Seed the type into the shared vocabulary, at setup, before any asset decodes. */
    public static void register() {
        Destinations.register(OWNER, DestinationType.of(TYPE, Trainer.class, Trainer.CODEC,
                TrainerDestinations::open));
    }

    private static boolean open(@Nonnull Trainer destination, @Nonnull DestinationContext ctx) {
        String placement = Trainers.resolve(destination.placement(), ctx.placementId(), ctx.npcId());
        // The pressed NPC anchors the page only when it stands for the resolved trainer; else the player does.
        boolean own = placement != null && ctx.placementId() != null && placement.equalsIgnoreCase(ctx.placementId());
        return TrainerPages.open(placement, ctx.store(), own ? ctx.pageAnchor() : ctx.playerReference(),
                ctx.player());
    }

    /** Open a trainer's page. */
    public static final class Trainer extends Destination {

        @Nullable protected String placement;

        public static final BuilderCodec<Trainer> CODEC = BuilderCodec.builder(Trainer.class, Trainer::new)
                .append(new KeyedCodec<>("Placement", Codec.STRING, false),
                        (d, v) -> d.placement = v, d -> d.placement)
                .documentation("The trainer to open, by its NPC placement id. Leave it out for the trainer the "
                        + "player is talking to, which is what a trainer's own conversation wants.").add()
                .build();

        public Trainer() {
        }

        /** The placement named, or null for the trainer the moment is about. */
        @Nullable
        public String placement() {
            return placement;
        }
    }
}
