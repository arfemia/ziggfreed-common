package com.ziggfreed.common.almanac.page;

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
 * The {@code Almanac} destination, unprefixed because the library owns the page behind it:
 *
 * <pre>{@code
 * "Open": "Almanac"                                      the season on now, else the first
 * "Open": { "Type": "Almanac", "Event": "Hallows_Eve" }   that season
 * }</pre>
 *
 * <p>A conversation line, a placement's press-F and a consumer's menu tile all open it through this one
 * value. It opens on the player's own ref, never the character's: the page reads the player's record.
 */
public final class AlmanacDestinations {

    /** The owner every registration here is attributed to. */
    public static final String OWNER = "ziggfreedcommon";

    /** The {@code Type} id content writes. */
    public static final String TYPE = "Almanac";

    /** The bare destination, for a consumer's menu tile. */
    public static final Almanac ALMANAC = Almanac.of(null);

    private AlmanacDestinations() {
    }

    /** Seed the type into the shared vocabulary, at setup, before any asset decodes. */
    public static void register() {
        Destinations.register(OWNER, DestinationType.of(TYPE, Almanac.class, Almanac.CODEC, AlmanacDestinations::open));
    }

    private static boolean open(@Nonnull Almanac destination, @Nonnull DestinationContext ctx) {
        return AlmanacPages.open(destination.getEvent(), ctx.store(), ctx.playerReference(), ctx.player());
    }

    /** Open the Almanac, on a named season or on the one that is on now. */
    public static final class Almanac extends Destination {

        @Nullable protected String event;

        public static final BuilderCodec<Almanac> CODEC = BuilderCodec.builder(Almanac.class, Almanac::new)
                .append(new KeyedCodec<>("Event", Codec.STRING, false),
                        (d, v) -> d.event = v, d -> d.event)
                .documentation("Which season to open the Almanac on, by its calendar event id. Leave it out to "
                        + "open on the season that is on now, else the first.").add()
                .build();

        public Almanac() {
        }

        /** Java-side construction; a null id means the season on now. */
        @Nonnull
        public static Almanac of(@Nullable String eventId) {
            Almanac d = new Almanac();
            d.event = eventId;
            return d;
        }

        /** The season to open on, or null for the one on now. */
        @Nullable
        public String getEvent() {
            return event == null || event.isBlank() ? null : event.trim();
        }
    }
}
