package com.ziggfreed.common.almanac.asset;

import java.io.IOException;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.bson.BsonValue;

import com.google.gson.JsonElement;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.schema.SchemaContext;
import com.hypixel.hytale.codec.schema.config.Schema;
import com.hypixel.hytale.codec.util.RawJsonReader;
import com.ziggfreed.common.codec.JsonTreeCodec;
import com.ziggfreed.common.ui.route.Destination;
import com.ziggfreed.common.ui.route.Destinations;
import com.ziggfreed.common.util.SafeLog;

/**
 * One line at the foot of a season's Almanac page that opens another screen: the words, and where they
 * go, in the shared destination vocabulary, so "See them in Achievements" opens the book with no edge
 * from the Almanac to it.
 *
 * <pre>{@code
 * "Links": [ { "TextKey": "almanac.spring_fair.link.book",
 *              "Destination": { "Type": "Achievements" } } ]
 * }</pre>
 *
 * <p>A season pack may link to a screen another mod brings, and run on a server without that mod. So a
 * destination whose {@code Type} nothing registered does NOT fail the read here, as it would anywhere
 * else: the link is left out, the page loads, and the season page reports it.
 */
public final class AlmanacLinkAsset {

    @Nullable private String textKey;
    @Nullable private Destination destination;

    public static final BuilderCodec<AlmanacLinkAsset> CODEC =
            BuilderCodec.builder(AlmanacLinkAsset.class, AlmanacLinkAsset::new)
                    .append(new KeyedCodec<>("TextKey", Codec.STRING, false),
                            (l, v) -> l.textKey = v, l -> l.textKey)
                    .documentation("The line's words, as a localization key.").add()
                    .append(new KeyedCodec<>("Destination", new LenientDestinationCodec(), false),
                            (l, v) -> l.destination = v, l -> l.destination)
                    .documentation("What the line opens, in the shared destination vocabulary. A Type no "
                            + "installed mod registers leaves the line out rather than failing the page.").add()
                    .build();

    public AlmanacLinkAsset() {
    }

    /** The words' key, trimmed, or null when unauthored. */
    @Nullable
    public String textKey() {
        return textKey == null || textKey.isBlank() ? null : textKey.trim();
    }

    /** Where the line goes, or null when unauthored or its Type is not registered. */
    @Nullable
    public Destination destination() {
        return destination;
    }

    /** Has words and somewhere to go. */
    public boolean usable() {
        return textKey() != null && destination != null;
    }

    /**
     * The shared destination codec, except that a {@code Type} nothing registered decodes to null (with a
     * warning) instead of failing the read. Exactly the spelling the decode itself matches is checked, so a
     * destination that would decode is never dropped.
     */
    private static final class LenientDestinationCodec implements Codec<Destination> {

        @Override
        @Nullable
        public Destination decode(BsonValue value, ExtraInfo extraInfo) {
            return registered(typeOf(JsonTreeCodec.object().decode(value, extraInfo)), extraInfo)
                    ? Destination.CODEC.decode(value, extraInfo) : null;
        }

        @Override
        public BsonValue encode(Destination destination, ExtraInfo extraInfo) {
            return Destination.CODEC.encode(destination, extraInfo);
        }

        @Override
        @Nullable
        public Destination decodeJson(RawJsonReader reader, ExtraInfo extraInfo) throws IOException {
            JsonElement value = JsonTreeCodec.object().decodeJson(reader, extraInfo);
            return registered(typeOf(value), extraInfo)
                    ? Destination.CODEC.decodeJson(RawJsonReader.fromJsonString(value.toString()), extraInfo) : null;
        }

        @Override
        @Nonnull
        public Schema toSchema(@Nonnull SchemaContext context) {
            return Destination.CODEC.toSchema(context);
        }

        /** Is {@code type} one the shared vocabulary decodes? Warns and answers false when it is not. */
        private static boolean registered(@Nullable String type, @Nonnull ExtraInfo extraInfo) {
            if (type != null && Destinations.registeredTypes().contains(type)) {
                return true;
            }
            SafeLog.warn("[almanac] " + extraInfo.peekKey() + ": a season link "
                    + (type == null ? "names no destination Type" : "opens '" + type
                            + "', which no installed mod registers") + ", so the link is left out");
            return false;
        }

        /** The {@code Type} a destination names: the bare string (trimmed, as the decode trims it), or the object's. */
        @Nullable
        private static String typeOf(@Nullable JsonElement value) {
            if (value == null || value.isJsonNull()) {
                return null;
            }
            if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
                return value.getAsString().trim();
            }
            if (value.isJsonObject()) {
                JsonElement type = value.getAsJsonObject().get(Destination.TYPE_KEY);
                return type != null && type.isJsonPrimitive() && type.getAsJsonPrimitive().isString()
                        ? type.getAsString() : null;
            }
            return null;
        }
    }
}
