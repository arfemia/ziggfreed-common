package com.ziggfreed.common.track.curve;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * A curve's {@code Pace}, read: how much a focused hour of play earns, in bands by the level each starts at (R609's
 * rising bands: 16k an hour from level 1 up to 108k late on the skill curve). It is what a reward priced in minutes
 * pays ({@link #price}): that many minutes of the band the pricing level stands in, a level below the first band
 * reading the first band and one past the last the last.
 *
 * <p>Which level a reward is priced at is {@link #pricingLevel} (R618): one-off content at the level it was made
 * for, a fixed {@code Level}; repeatable content at the player's own level, held at a {@code MaxLevel} so easy
 * content stops paying more past it.
 *
 * <p>Pure: built from the written map, no store, no player.
 */
public final class PaceTable {

    /** A curve that writes no Pace: every price is 0. */
    public static final PaceTable NONE = new PaceTable(new int[0], new double[0]);

    /** One band: from this level on, this much an hour. */
    public record Band(int from, double perHour) {
    }

    private final int[] from;
    private final double[] perHour;

    private PaceTable(@Nonnull int[] from, @Nonnull double[] perHour) {
        this.from = from;
        this.perHour = perHour;
    }

    /**
     * The bands {@code written} names, by the level each starts at: a key that is no whole number of 1 or more, or a
     * value that is no number above 0, is passed over (an owner's or a pack's 0 takes an inherited band out).
     */
    @Nonnull
    public static PaceTable of(@Nullable Map<String, Double> written) {
        if (written == null || written.isEmpty()) {
            return NONE;
        }
        TreeMap<Integer, Double> sorted = new TreeMap<>();
        for (Map.Entry<String, Double> entry : written.entrySet()) {
            Integer level = levelKey(entry.getKey());
            Double value = entry.getValue();
            if (level != null && value != null && Double.isFinite(value) && value > 0.0) {
                sorted.put(level, value);
            }
        }
        if (sorted.isEmpty()) {
            return NONE;
        }
        int[] levels = new int[sorted.size()];
        double[] hours = new double[sorted.size()];
        int i = 0;
        for (Map.Entry<Integer, Double> band : sorted.entrySet()) {
            levels[i] = band.getKey();
            hours[i] = band.getValue();
            i++;
        }
        return new PaceTable(levels, hours);
    }

    /** A band key read as its level: a whole number of 1 or more, spaces aside; null for anything else. */
    @Nullable
    public static Integer levelKey(@Nullable String key) {
        if (key == null) {
            return null;
        }
        try {
            int level = Integer.parseInt(key.trim());
            return level >= 1 ? level : null;
        } catch (NumberFormatException notALevel) {
            return null;
        }
    }

    /** Does this curve write no usable band? */
    public boolean isEmpty() {
        return from.length == 0;
    }

    /** The bands in level order. */
    @Nonnull
    public List<Band> bands() {
        List<Band> out = new ArrayList<>(from.length);
        for (int i = 0; i < from.length; i++) {
            out.add(new Band(from[i], perHour[i]));
        }
        return out;
    }

    /**
     * What a focused hour earns at {@code level}: the band with the highest start at or below it, the first band for
     * a level below every start; 0 when there is none.
     */
    public double perHour(int level) {
        if (from.length == 0) {
            return 0.0;
        }
        double at = perHour[0];
        for (int i = 0; i < from.length && from[i] <= level; i++) {
            at = perHour[i];
        }
        return at;
    }

    /**
     * What {@code minutes} of play at {@code level} earns, whole (rounded half up), 0 or more: 0 for no band, and for
     * minutes that are no number above 0.
     */
    public long price(double minutes, int level) {
        if (!Double.isFinite(minutes) || minutes <= 0.0) {
            return 0L;
        }
        double paid = minutes * perHour(level) / 60.0;
        return Double.isFinite(paid) && paid > 0.0 ? Math.round(paid) : 0L;
    }

    /**
     * The level a reward is priced at (R618): {@code fixedLevel} when it writes one (one-off content, at the level it
     * was made for), else the player's {@code playerLevel} held at {@code maxLevel} when it writes one (repeatable
     * content, which stops paying more past it); never below 1. A {@code fixedLevel} or {@code maxLevel} of 0 or less
     * is unwritten.
     */
    public static int pricingLevel(int playerLevel, int fixedLevel, int maxLevel) {
        if (fixedLevel > 0) {
            return fixedLevel;
        }
        int level = Math.max(1, playerLevel);
        return maxLevel > 0 ? Math.min(level, maxLevel) : level;
    }

    @Override
    public String toString() {
        return "PaceTable" + bands();
    }
}
