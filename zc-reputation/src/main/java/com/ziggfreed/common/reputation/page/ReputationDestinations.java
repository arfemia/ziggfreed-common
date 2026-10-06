package com.ziggfreed.common.reputation.page;

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
 * The {@code Reputation} destination, unprefixed because the library owns the page behind it:
 *
 * <pre>{@code
 * "Open": "Reputation"                                              the first reputation the player has met
 * "Open": { "Type": "Reputation", "Reputation": "Your_Traders" }     that one
 * }</pre>
 *
 * <p>A conversation line, a placement's press-F and a consumer's menu tile all open it through this one
 * value. It opens on the player's own ref, never the character's: the page reads the player's standing.
 */
public final class ReputationDestinations {

    /** The owner every registration here is attributed to. */
    public static final String OWNER = "ziggfreedcommon";

    /** The {@code Type} id content writes. */
    public static final String TYPE = "Reputation";

    /** The bare destination, for a menu tab or a consumer's tile. */
    public static final Reputation REPUTATION = Reputation.of(null);

    private ReputationDestinations() {
    }

    /** Seed the type into the shared vocabulary, at setup, before any asset decodes. */
    public static void register() {
        Destinations.register(OWNER, DestinationType.of(TYPE, Reputation.class, Reputation.CODEC,
                ReputationDestinations::open));
    }

    private static boolean open(@Nonnull Reputation destination, @Nonnull DestinationContext ctx) {
        return ReputationPages.open(destination.getReputation(), ctx.store(), ctx.playerReference(), ctx.player());
    }

    /** Open the Reputation page, on a named reputation or on the first one the player has met. */
    public static final class Reputation extends Destination {

        @Nullable protected String reputation;

        public static final BuilderCodec<Reputation> CODEC = BuilderCodec.builder(Reputation.class, Reputation::new)
                .append(new KeyedCodec<>("Reputation", Codec.STRING, false),
                        (d, v) -> d.reputation = v, d -> d.reputation)
                .documentation("Which reputation to open the page on, by its id. Leave it out to open on the "
                        + "first one the player has met.").add()
                .build();

        public Reputation() {
        }

        /** Java-side construction; a null id means the first met reputation. */
        @Nonnull
        public static Reputation of(@Nullable String reputationId) {
            Reputation d = new Reputation();
            d.reputation = reputationId;
            return d;
        }

        /** The reputation to open on, or null for the first met one. */
        @Nullable
        public String getReputation() {
            return reputation == null || reputation.isBlank() ? null : reputation.trim();
        }
    }
}
