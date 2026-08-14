package com.ziggfreed.common.rotation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;
import java.util.function.ToDoubleFunction;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * HOW a rotating pool turns its eligible candidates into the set on show. One implementation per
 * {@link SelectionSpec} {@code Type}.
 *
 * <p>Two are shipped and both are here, because they are the two answers that mean something in any
 * game: draw a few of them by weight, or show all of them. Anything else - a per-player draw, a
 * curve over a player's progress, a hand-ordered rota - is a consumer's own algorithm registered
 * into {@link SelectionStrategies} beside these.
 *
 * <p>A strategy is handed everything the draw needs and holds nothing: the slots to fill, the
 * candidates, how to read an id and a weight off one, which slots accept which, and the seed. It
 * must be deterministic for a given seed, or the rotation stops being the same for every player.
 */
public interface SelectionStrategy {

    /**
     * Draw the active set.
     *
     * @param slots        the positions to fill, or null / empty to draw {@code defaultCount} from
     *                     the whole pool
     * @param seed         the deterministic seed, from {@link PoolSeed}
     * @param defaultCount how many to draw when there are no slots
     */
    @Nonnull
    <T> WeightedSlotDraw.DrawResult<T> draw(@Nonnull List<T> pool, @Nullable List<PoolSlot> slots,
            @Nonnull Function<T, String> idFn, @Nonnull ToDoubleFunction<T> weightFn,
            @Nonnull WeightedSlotDraw.SlotMatcher<T> matcher, long seed, int defaultCount);

    /** The weighted draw: {@code Count} distinct candidates per slot, biased by weight, seeded. */
    final class WeightedRandom implements SelectionStrategy {

        @Override
        @Nonnull
        public <T> WeightedSlotDraw.DrawResult<T> draw(@Nonnull List<T> pool,
                @Nullable List<PoolSlot> slots, @Nonnull Function<T, String> idFn,
                @Nonnull ToDoubleFunction<T> weightFn,
                @Nonnull WeightedSlotDraw.SlotMatcher<T> matcher, long seed, int defaultCount) {
            return WeightedSlotDraw.drawDetailed(pool, slots, idFn, weightFn, matcher, seed, defaultCount);
        }
    }

    /**
     * Everything eligible, in id order, ignoring weights and the seed entirely.
     *
     * <p>With slots, each slot contributes every candidate it accepts that no earlier slot already
     * took, up to that slot's {@code Count}; with none, the whole pool. It is what a storefront
     * that rotates its PRICES rather than its stock wants, and what a small pool wants when hiding
     * half of it would leave a board looking broken.
     */
    final class All implements SelectionStrategy {

        @Override
        @Nonnull
        public <T> WeightedSlotDraw.DrawResult<T> draw(@Nonnull List<T> pool,
                @Nullable List<PoolSlot> slots, @Nonnull Function<T, String> idFn,
                @Nonnull ToDoubleFunction<T> weightFn,
                @Nonnull WeightedSlotDraw.SlotMatcher<T> matcher, long seed, int defaultCount) {
            List<T> sorted = new ArrayList<>(pool);
            sorted.sort(Comparator.comparing(idFn));

            List<T> items = new ArrayList<>();
            List<PoolSlot> slotByPosition = new ArrayList<>();
            if (slots == null || slots.isEmpty()) {
                for (T candidate : sorted) {
                    items.add(candidate);
                    slotByPosition.add(null);
                }
                return new WeightedSlotDraw.DrawResult<>(items, slotByPosition);
            }
            List<String> used = new ArrayList<>();
            for (PoolSlot slot : slots) {
                if (slot == null) {
                    continue;
                }
                int taken = 0;
                for (T candidate : sorted) {
                    if (taken >= slot.count()) {
                        break;
                    }
                    String id = idFn.apply(candidate);
                    if (used.contains(id) || !matcher.matches(candidate, slot)) {
                        continue;
                    }
                    items.add(candidate);
                    slotByPosition.add(slot);
                    used.add(id);
                    taken++;
                }
            }
            return new WeightedSlotDraw.DrawResult<>(items, slotByPosition);
        }
    }
}
