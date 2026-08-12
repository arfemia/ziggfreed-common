package com.ziggfreed.common.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.DoubleSupplier;
import java.util.function.ToDoubleFunction;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * The ONE weighted-selection primitive: "given items and their weights, pick one" and "pick N",
 * over an INJECTED {@code [0,1)} sample source so every caller's roll is reproducible and testable
 * without a live server.
 *
 * <p>Weighted picking is the single most re-derived piece of arithmetic in a loot/rotation/spawn
 * codebase, and every copy drifts a little: one treats a negative weight as a penalty, one picks
 * uniformly when all weights are zero, one silently returns the last entry. This class fixes the
 * answers once:
 *
 * <ul>
 *   <li>a weight is read as {@code max(0, w)}, so a negative number is "never picked" rather than a
 *       value that quietly bends the whole distribution;</li>
 *   <li>a null item, or one whose weight function throws, is skipped;</li>
 *   <li>when EVERY eligible weight is zero the pick is UNIFORM rather than empty - a table whose
 *       author forgot the weights still hands something over instead of going dark;</li>
 *   <li>an empty candidate list picks nothing ({@code null} / an empty list), never an exception.</li>
 * </ul>
 *
 * <p>The sample source is a {@link DoubleSupplier} answering {@code [0,1)}. Pass {@code random::nextDouble}
 * for an ordinary stream, or {@link #from(SplitMix64)} for a seeded one that survives a restart.
 * {@link #one} consumes EXACTLY ONE sample per call, so a caller counting draws can predict the stream.
 */
public final class WeightedPick {

    private WeightedPick() {
    }

    // ==================== sample sources ====================

    /** A {@code [0,1)} source over {@code random} (one {@code nextDouble} per sample). */
    @Nonnull
    public static DoubleSupplier from(@Nonnull Random random) {
        return random::nextDouble;
    }

    /** A {@code [0,1)} source over a seeded {@link SplitMix64}, for a roll that must be reproducible. */
    @Nonnull
    public static DoubleSupplier from(@Nonnull SplitMix64 rng) {
        return rng::nextDouble;
    }

    // ==================== picking ====================

    /**
     * One item chosen in proportion to its weight, or {@code null} when {@code items} holds nothing
     * pickable. Consumes exactly one sample.
     *
     * <p>The walk is the classic cumulative subtract: {@code r = sample * totalWeight}, then step
     * through the candidates taking each weight off {@code r} until it runs out. An all-zero-weight
     * set falls back to a uniform index off that same sample.
     */
    @Nullable
    public static <T> T one(@Nullable List<T> items, @Nonnull ToDoubleFunction<T> weightOf,
            @Nonnull DoubleSupplier sample) {
        if (items == null || items.isEmpty()) {
            return null;
        }
        double total = totalWeight(items, weightOf);
        double roll = sample.getAsDouble();
        if (total <= 0.0) {
            int index = (int) (roll * items.size());
            if (index < 0) {
                index = 0;
            }
            if (index >= items.size()) {
                index = items.size() - 1;
            }
            return items.get(index);
        }
        double remaining = roll * total;
        T last = null;
        for (T item : items) {
            double weight = weight(item, weightOf);
            // A zero-weight entry owns no band, so it is stepped OVER rather than tested. Testing it
            // would hand it the pick whenever the running remainder happened to be exactly 0, which
            // is precisely what a weight of 0 is supposed to rule out.
            if (weight <= 0.0) {
                continue;
            }
            last = item;
            remaining -= weight;
            if (remaining <= 0.0) {
                return item;
            }
        }
        // Floating-point drift can leave a sliver after the last subtraction; the last pickable
        // entry owns it rather than the call answering "nothing" with weight still on the table.
        return last;
    }

    /**
     * Up to {@code count} picks, each in proportion to weight, in pick order.
     *
     * <p>{@code unique} is the one knob that separates the two shapes a caller ever wants: false
     * draws WITH replacement (the same entry can come up twice, which is what a loot pool means by
     * "three rolls"), true removes each pick so a set of distinct outcomes comes back. A unique draw
     * naturally stops early once the candidates run out; a non-unique one never does.
     *
     * <p>Consumes one sample per attempted pick, so the stream position is {@code count} draws for a
     * with-replacement call and at most that for a unique one.
     */
    @Nonnull
    public static <T> List<T> some(@Nullable List<T> items, @Nonnull ToDoubleFunction<T> weightOf,
            int count, boolean unique, @Nonnull DoubleSupplier sample) {
        List<T> out = new ArrayList<>(Math.max(0, count));
        if (items == null || items.isEmpty() || count <= 0) {
            return out;
        }
        if (!unique) {
            for (int i = 0; i < count; i++) {
                T picked = one(items, weightOf, sample);
                if (picked == null) {
                    break;
                }
                out.add(picked);
            }
            return out;
        }
        List<T> pool = new ArrayList<>(items);
        for (int i = 0; i < count && !pool.isEmpty(); i++) {
            T picked = one(pool, weightOf, sample);
            if (picked == null) {
                break;
            }
            out.add(picked);
            pool.remove(picked);
        }
        return out;
    }

    /**
     * The summed pickable weight of {@code items} - what {@link #one} normalizes against. Useful on
     * its own to a caller that wants to report a per-entry probability.
     */
    public static <T> double totalWeight(@Nullable List<T> items, @Nonnull ToDoubleFunction<T> weightOf) {
        if (items == null) {
            return 0.0;
        }
        double total = 0.0;
        for (T item : items) {
            total += weight(item, weightOf);
        }
        return total;
    }

    /** One item's weight, floored at 0, with a null item or a throwing/non-finite weight reading as 0. */
    private static <T> double weight(@Nullable T item, @Nonnull ToDoubleFunction<T> weightOf) {
        if (item == null) {
            return 0.0;
        }
        double w;
        try {
            w = weightOf.applyAsDouble(item);
        } catch (Throwable t) {
            return 0.0;
        }
        return Double.isFinite(w) && w > 0.0 ? w : 0.0;
    }
}
