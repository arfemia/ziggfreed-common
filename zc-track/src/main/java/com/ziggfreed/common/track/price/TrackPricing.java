package com.ziggfreed.common.track.price;

import java.util.UUID;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.loot.reward.RewardSpec;
import com.ziggfreed.common.track.TrackDef;
import com.ziggfreed.common.track.TrackKey;
import com.ziggfreed.common.track.TrackReading;
import com.ziggfreed.common.track.TrackService;
import com.ziggfreed.common.track.curve.PaceTable;

/**
 * The one reading of a reward priced in minutes of play (R609, R618), shared by the Track reward kind, the
 * native {@code ZigTrack} reward and the Track lamp: which price row it names ({@code Price}, a row of
 * {@link TrackPriceConfig}), the {@code Minutes}, {@code Level} and {@code MaxLevel} it writes over that row's, and
 * what those minutes come to on a track for a player ({@link #price}: the Pace band of the track's curve at the
 * pricing level, {@link PaceTable#pricingLevel}).
 *
 * <p>Pricing reads the player's current climb on the paid track, live on its own thread; a player not live here
 * cannot be priced ({@link #UNPRICED}), and the caller keeps the reward unpriced to price it later.
 */
public final class TrackPricing {

    /** A reward's price row, by id. */
    public static final String PARAM_PRICE = "price";
    /** A reward's minutes, over its row's. */
    public static final String PARAM_MINUTES = "minutes";
    /** A reward's fixed pricing level, over its row's. */
    public static final String PARAM_LEVEL = "level";
    /** A reward's highest pricing level, over its row's. */
    public static final String PARAM_MAX_LEVEL = "maxlevel";

    /** What {@link #priceFor} answers when the player's climb cannot be read here. */
    public static final long UNPRICED = -1L;

    /**
     * A minutes price, resolved: how many minutes, the fixed level they are priced at (0 for the player's own) and
     * the highest level the player's own is priced at (0 for none).
     */
    public record Priced(double minutes, int level, int maxLevel) {

        /** Does it pay anything? */
        public boolean pays() {
            return minutes > 0.0;
        }
    }

    private TrackPricing() {
    }

    /** Is {@code spec} priced in minutes: does it name a {@code Price} or write {@code Minutes}? */
    public static boolean isPriced(@Nonnull RewardSpec spec) {
        return written(spec.param(PARAM_PRICE)) || written(spec.param(PARAM_MINUTES));
    }

    /**
     * {@code spec}'s minutes price: its price row's leaves, each one {@code spec} writes over it; null when it is not
     * priced in minutes ({@link #isPriced}).
     */
    @Nullable
    public static Priced of(@Nonnull RewardSpec spec) {
        if (!isPriced(spec)) {
            return null;
        }
        return resolve(spec.param(PARAM_PRICE), number(spec.param(PARAM_MINUTES)), whole(spec.param(PARAM_LEVEL)),
                whole(spec.param(PARAM_MAX_LEVEL)));
    }

    /**
     * The price row {@code priceId} names (a row no layer holds reads as none), with {@code minutes}, {@code level}
     * and {@code maxLevel} over its own wherever they are given.
     */
    @Nonnull
    public static Priced resolve(@Nullable String priceId, @Nullable Double minutes, @Nullable Integer level,
            @Nullable Integer maxLevel) {
        TrackPriceAsset row = TrackPriceConfig.getInstance().price(priceId);
        return over(row, minutes, level, maxLevel);
    }

    /** {@code row}'s leaves (none for a null row) with each given one over it. Pure. */
    @Nonnull
    public static Priced over(@Nullable TrackPriceAsset row, @Nullable Double minutes, @Nullable Integer level,
            @Nullable Integer maxLevel) {
        double m = minutes != null && Double.isFinite(minutes) ? Math.max(0.0, minutes)
                : row == null ? 0.0 : row.minutes();
        int l = level != null ? Math.max(0, level) : row == null ? 0 : row.level();
        int max = maxLevel != null ? Math.max(0, maxLevel) : row == null ? 0 : row.maxLevel();
        return new Priced(m, l, max);
    }

    /** What {@code priced} comes to on {@code def} for a player whose current climb there stands at {@code level}. */
    public static long price(@Nonnull TrackDef def, @Nonnull Priced priced, int level) {
        return def.pace().price(priced.minutes(), PaceTable.pricingLevel(level, priced.level(), priced.maxLevel()));
    }

    /**
     * What {@code priced} comes to on {@code trackId} for {@code player}, read live on the player's own thread:
     * {@link #UNPRICED} when the track is not usable or the player's climb cannot be read here, else 0 or more.
     */
    public static long priceFor(@Nonnull UUID player, @Nonnull String trackId, @Nonnull Priced priced) {
        TrackDef def = TrackService.def(trackId);
        if (def == null) {
            return UNPRICED;
        }
        if (priced.level() > 0) {
            // A fixed level needs no climb read: the same for everyone, live or not.
            return price(def, priced, priced.level());
        }
        TrackReading reading = TrackService.read(TrackKey.player(player, trackId));
        return reading == null ? UNPRICED : price(def, priced, reading.level());
    }

    private static boolean written(@Nullable String value) {
        return value != null && !value.isBlank();
    }

    @Nullable
    private static Double number(@Nullable String text) {
        if (!written(text)) {
            return null;
        }
        try {
            double value = Double.parseDouble(text.trim());
            return Double.isFinite(value) ? value : null;
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }

    @Nullable
    private static Integer whole(@Nullable String text) {
        Double value = number(text);
        return value == null ? null : (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, value));
    }
}
