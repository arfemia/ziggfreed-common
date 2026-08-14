package com.ziggfreed.common.rotation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.function.Function;
import java.util.function.ToDoubleFunction;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * The deterministic weighted draw every rotating pool runs: per slot, pick {@code Count} distinct
 * candidates out of the ones that slot accepts, biased by weight.
 *
 * <p><b>Reproducible on purpose.</b> The pool is sorted by id and the random source is seeded from
 * the caller's {@link PoolSeed}, so the same pool, period and slots yield the same answer on every
 * server and after every restart. That is the whole reason a rotation needs no persisted state.
 *
 * <p>Type-agnostic over the candidate type: a caller supplies an id extractor, a weight function
 * and a {@link SlotMatcher}, so a bounty pool and a storefront pool run this one draw rather than
 * one apiece.
 *
 * <p>With no slots at all, {@code defaultCount} candidates are drawn from the whole pool.
 */
public final class WeightedSlotDraw {

    private WeightedSlotDraw() {
    }

    /** Does this candidate qualify for that slot? Supplied by the caller, whose grading it is. */
    @FunctionalInterface
    public interface SlotMatcher<T> {
        boolean matches(T candidate, PoolSlot slot);
    }

    /**
     * A draw, plus which slot governed each position.
     *
     * <p>{@link #slotByPosition} is the same length as {@link #items}: entry {@code i} is the slot
     * that produced position {@code i}, or null for the slot-less path. A single-position reroll
     * needs it to know which filter to redraw that position from.
     */
    public record DrawResult<T>(@Nonnull List<T> items, @Nonnull List<PoolSlot> slotByPosition) {

        /** An empty draw. */
        @Nonnull
        public static <T> DrawResult<T> empty() {
            return new DrawResult<>(List.of(), List.of());
        }

        /** The slot governing {@code position}, or null when nothing does. */
        @Nullable
        public PoolSlot slotAt(int position) {
            return (position >= 0 && position < slotByPosition.size()) ? slotByPosition.get(position) : null;
        }

        /** How many positions were filled. */
        public int size() {
            return items.size();
        }
    }

    /** The drawn candidates only, for a caller with no reroll to layer on top. */
    @Nonnull
    public static <T> List<T> draw(@Nonnull List<T> pool, @Nullable List<PoolSlot> slots,
            @Nonnull Function<T, String> idFn, @Nonnull ToDoubleFunction<T> weightFn,
            @Nonnull SlotMatcher<T> matcher, long seed, int defaultCount) {
        return drawDetailed(pool, slots, idFn, weightFn, matcher, seed, defaultCount).items();
    }

    /**
     * The same draw as {@link #draw}, also answering which slot governed each position. Identical
     * sort, seed and random sequence, so a caller that only wanted the candidates sees no
     * difference.
     */
    @Nonnull
    public static <T> DrawResult<T> drawDetailed(@Nonnull List<T> pool, @Nullable List<PoolSlot> slots,
            @Nonnull Function<T, String> idFn, @Nonnull ToDoubleFunction<T> weightFn,
            @Nonnull SlotMatcher<T> matcher, long seed, int defaultCount) {
        List<T> sorted = new ArrayList<>(pool);
        sorted.sort(Comparator.comparing(idFn));

        Random rng = new Random(seed);
        List<T> result = new ArrayList<>();
        List<PoolSlot> slotByPosition = new ArrayList<>();
        Set<String> used = new HashSet<>();

        if (slots == null || slots.isEmpty()) {
            drawInto(result, used, sorted, rng, defaultCount, idFn, weightFn);
            while (slotByPosition.size() < result.size()) {
                slotByPosition.add(null);
            }
            return new DrawResult<>(result, slotByPosition);
        }
        for (PoolSlot slot : slots) {
            if (slot == null) {
                continue;
            }
            List<T> candidates = new ArrayList<>();
            for (T candidate : sorted) {
                if (matcher.matches(candidate, slot)) {
                    candidates.add(candidate);
                }
            }
            drawInto(result, used, candidates, rng, slot.count(), idFn, weightFn);
            while (slotByPosition.size() < result.size()) {
                slotByPosition.add(slot);
            }
        }
        return new DrawResult<>(result, slotByPosition);
    }

    /**
     * One replacement candidate for a single position, drawn from {@code slot}'s candidates (the
     * whole pool when the position had no slot), excluding every id in {@code exclude}.
     *
     * <p>Answers null when no distinct alternative exists, which is what lets a caller refuse a
     * reroll BEFORE charging for it rather than charging for a guaranteed no-op.
     */
    @Nullable
    public static <T> T drawReplacement(@Nonnull List<T> pool, @Nullable PoolSlot slot,
            @Nonnull Function<T, String> idFn, @Nonnull ToDoubleFunction<T> weightFn,
            @Nonnull SlotMatcher<T> matcher, @Nonnull Set<String> exclude, long seed) {
        List<T> sorted = new ArrayList<>(pool);
        sorted.sort(Comparator.comparing(idFn));
        List<T> candidates = new ArrayList<>();
        for (T candidate : sorted) {
            if ((slot == null || matcher.matches(candidate, slot)) && !exclude.contains(idFn.apply(candidate))) {
                candidates.add(candidate);
            }
        }
        return weightedPick(candidates, new Random(seed), weightFn);
    }

    private static <T> void drawInto(@Nonnull List<T> result, @Nonnull Set<String> used,
            @Nonnull List<T> candidates, @Nonnull Random rng, int count,
            @Nonnull Function<T, String> idFn, @Nonnull ToDoubleFunction<T> weightFn) {
        List<T> pool = new ArrayList<>(candidates);
        pool.removeIf(candidate -> used.contains(idFn.apply(candidate)));
        for (int i = 0; i < count; i++) {
            T pick = weightedPick(pool, rng, weightFn);
            if (pick == null) {
                return;
            }
            result.add(pick);
            used.add(idFn.apply(pick));
            pool.remove(pick);
        }
    }

    @Nullable
    private static <T> T weightedPick(@Nonnull List<T> candidates, @Nonnull Random rng,
            @Nonnull ToDoubleFunction<T> weightFn) {
        if (candidates.isEmpty()) {
            return null;
        }
        double total = 0;
        for (T candidate : candidates) {
            total += weightOf(weightFn, candidate);
        }
        double roll = rng.nextDouble() * total;
        for (T candidate : candidates) {
            roll -= weightOf(weightFn, candidate);
            if (roll <= 0) {
                return candidate;
            }
        }
        return candidates.get(candidates.size() - 1);
    }

    /** A weight of zero or less reads as one: an unweighted candidate is an ordinary candidate. */
    private static <T> double weightOf(@Nonnull ToDoubleFunction<T> weightFn, T candidate) {
        double weight = weightFn.applyAsDouble(candidate);
        return (Double.isFinite(weight) && weight > 0) ? weight : 1.0;
    }
}
