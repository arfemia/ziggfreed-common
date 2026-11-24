package com.ziggfreed.common.track.lamp;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.type.item.config.metadata.ItemDisplayMetadata;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.ziggfreed.common.track.TrackText;
import com.ziggfreed.common.track.price.TrackPricing;

/**
 * What one lamp holds, under the {@value #KEY} metadata key of its stack (R618's lamp: one-off content paying minutes
 * of play into a track the player chooses): how much it pays, priced in minutes when it is used, at the level of the
 * track each pick goes to ({@code Price}, a row of the XP price table, and its {@code Minutes}, {@code Level} and
 * {@code MaxLevel} over the row's, as a Track reward reads them, {@link TrackPricing}); how many different tracks the
 * player picks ({@code Picks}); which tracks it may go to ({@code Tracks} by id, else every track of
 * {@code Category}, several split by commas, else every track); and the level a track must stand at to be picked
 * ({@code MinLevel}).
 *
 * <p>One item ({@value #ITEM_ID}) stands for a lamp of any size: the {@code Track_Lamp} reward kind or
 * {@code /zigtrack lamp} mints one ({@link #mint}), and two lamps with the same numbers carry the same document and
 * stack. Using it ({@code ZigTrackLamp}) opens the picker ({@link TrackLampPage}); the lamp is spent only when the
 * picks are paid ({@link TrackLamps#rub}). A stack with no document, or one that pays nothing, is no lamp.
 */
public final class TrackLamp {

    /** The metadata document's key on the stack. */
    public static final String KEY = "ZigTrackLamp";

    /** The library's lamp item. */
    public static final String ITEM_ID = "Zig_Track_Lamp";

    /** The most tracks one lamp asks the player to pick. */
    public static final int MAX_PICKS = 5;

    @Nullable private String price;
    @Nullable private Double minutes;
    @Nullable private Integer level;
    @Nullable private Integer maxLevel;
    @Nullable private Integer picks;
    @Nullable private String[] tracks;
    @Nullable private String category;
    @Nullable private Integer minLevel;

    public static final BuilderCodec<TrackLamp> CODEC = BuilderCodec.builder(TrackLamp.class, TrackLamp::new)
            .append(new KeyedCodec<>("Price", Codec.STRING), (t, v) -> t.price = v, t -> t.price)
            .documentation("A row of the XP price table, by id: how many minutes of play each pick pays.").add()
            .append(new KeyedCodec<>("Minutes", Codec.DOUBLE), (t, v) -> t.minutes = v, t -> t.minutes)
            .documentation("Minutes of play each pick pays, over the price row's.").add()
            .append(new KeyedCodec<>("Level", Codec.INTEGER), (t, v) -> t.level = v, t -> t.level)
            .documentation("Price each pick at this level whatever the track's, over the price row's.").add()
            .append(new KeyedCodec<>("MaxLevel", Codec.INTEGER), (t, v) -> t.maxLevel = v, t -> t.maxLevel)
            .documentation("Price each pick at the player's level on its track, never above this one.").add()
            .append(new KeyedCodec<>("Picks", Codec.INTEGER), (t, v) -> t.picks = v, t -> t.picks)
            .documentation("How many different tracks the player picks, each paid in full. 1 when absent.").add()
            .append(new KeyedCodec<>("Tracks", Codec.STRING_ARRAY), (t, v) -> t.tracks = v, t -> t.tracks)
            .documentation("The tracks it may go to, by id. Absent for every track of the Category.").add()
            .append(new KeyedCodec<>("Category", Codec.STRING), (t, v) -> t.category = v, t -> t.category)
            .documentation("With no Tracks, every track of this category (several split by commas). Absent for "
                    + "every track.").add()
            .append(new KeyedCodec<>("MinLevel", Codec.INTEGER), (t, v) -> t.minLevel = v, t -> t.minLevel)
            .documentation("The level a track must stand at for the player to pick it. Absent for any.").add()
            .build();

    public static final KeyedCodec<TrackLamp> KEYED_CODEC = new KeyedCodec<>(KEY, CODEC);

    public TrackLamp() {
    }

    /** A lamp of these numbers; each null for none (a blank id, a pick count under 1 and an empty list read as none). */
    public TrackLamp(@Nullable String price, @Nullable Double minutes, @Nullable Integer level,
            @Nullable Integer maxLevel, @Nullable Integer picks, @Nullable List<String> tracks,
            @Nullable String category, @Nullable Integer minLevel) {
        this.price = blankToNull(price);
        this.minutes = minutes != null && Double.isFinite(minutes) && minutes > 0.0 ? minutes : null;
        this.level = level != null && level > 0 ? level : null;
        this.maxLevel = maxLevel != null && maxLevel > 0 ? maxLevel : null;
        this.picks = picks != null && picks > 1 ? Math.min(MAX_PICKS, picks) : null;
        List<String> ids = new ArrayList<>();
        if (tracks != null) {
            for (String id : tracks) {
                String clean = blankToNull(id);
                if (clean != null && !ids.contains(clean)) {
                    ids.add(clean);
                }
            }
        }
        this.tracks = ids.isEmpty() ? null : ids.toArray(String[]::new);
        this.category = blankToNull(category);
        this.minLevel = minLevel != null && minLevel > 1 ? minLevel : null;
    }

    /** What each pick pays, resolved against the price table now. */
    @Nonnull
    public TrackPricing.Priced priced() {
        return TrackPricing.resolve(price, minutes, level, maxLevel);
    }

    /** The price row named, or null. */
    @Nullable
    public String price() {
        return blankToNull(price);
    }

    /** The minutes written over the price row's, or null. */
    @Nullable
    public Double minutes() {
        return minutes;
    }

    /** The fixed pricing level written, or null. */
    @Nullable
    public Integer level() {
        return level;
    }

    /** The highest pricing level written, or null. */
    @Nullable
    public Integer maxLevel() {
        return maxLevel;
    }

    /** How many different tracks the player picks, 1 to {@link #MAX_PICKS}. */
    public int picks() {
        return picks == null ? 1 : Math.max(1, Math.min(MAX_PICKS, picks));
    }

    /** The tracks named, in order, or an empty list for the category's (or every) track. */
    @Nonnull
    public List<String> tracks() {
        return tracks == null ? List.of() : List.of(tracks);
    }

    /** The category named, or null. */
    @Nullable
    public String category() {
        return blankToNull(category);
    }

    /** The level a track must stand at to be picked, 1 or more. */
    public int minLevel() {
        return minLevel == null ? 1 : Math.max(1, minLevel);
    }

    /** Does it name something to pay: a price row or minutes of its own? */
    public boolean pays() {
        return price() != null || minutes != null;
    }

    /** The lamp {@code stack} carries, or null when it carries none, or one that pays nothing. */
    @Nullable
    public static TrackLamp of(@Nullable ItemStack stack) {
        if (ItemStack.isEmpty(stack)) {
            return null;
        }
        try {
            TrackLamp lamp = stack.getFromMetadataOrNull(KEYED_CODEC);
            return lamp == null || !lamp.pays() ? null : lamp;
        } catch (Throwable unreadable) {
            return null;
        }
    }

    /** {@code quantity} of the lamp item carrying {@code lamp}, its tooltip saying what using it does. */
    @Nonnull
    public static ItemStack mint(@Nonnull TrackLamp lamp, int quantity) {
        return new ItemStack(ITEM_ID, Math.max(1, quantity)).withMetadata(KEYED_CODEC, lamp)
                .withMetadata(ItemDisplayMetadata.KEYED_CODEC, new ItemDisplayMetadata(null, description(lamp)));
    }

    /** What using {@code lamp} does, as its tooltip reads. */
    @Nonnull
    public static Message description(@Nonnull TrackLamp lamp) {
        double each = lamp.priced().minutes();
        return lamp.picks() > 1 ? TrackText.line("lamp.item.many", each, lamp.picks())
                : TrackText.line("lamp.item.one", each);
    }

    @Nullable
    private static String blankToNull(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof TrackLamp that && Objects.equals(fold(price()), fold(that.price()))
                && Objects.equals(minutes, that.minutes) && Objects.equals(level, that.level)
                && Objects.equals(maxLevel, that.maxLevel) && picks() == that.picks()
                && Objects.equals(folded(tracks()), folded(that.tracks()))
                && Objects.equals(fold(category()), fold(that.category())) && minLevel() == that.minLevel();
    }

    @Override
    public int hashCode() {
        return Objects.hash(fold(price()), minutes, level, maxLevel, picks(), folded(tracks()), fold(category()),
                minLevel());
    }

    @Nullable
    private static String fold(@Nullable String value) {
        return value == null ? null : value.toLowerCase(Locale.ROOT);
    }

    @Nonnull
    private static List<String> folded(@Nonnull List<String> values) {
        List<String> out = new ArrayList<>();
        for (String value : values) {
            out.add(fold(value));
        }
        return out;
    }

    @Override
    public String toString() {
        return "TrackLamp{price=" + price() + ", minutes=" + minutes + ", level=" + level + ", maxLevel=" + maxLevel
                + ", picks=" + picks() + ", tracks=" + Arrays.toString(tracks) + ", category=" + category()
                + ", minLevel=" + minLevel() + "}";
    }
}
