package com.ziggfreed.common.commerce;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * One rotating pool's reroll state for ONE period, as a whole.
 *
 * <p>The per-question reads on {@link CommerceStore} answer what a live reroll asks - the overrides
 * to lay over the draw, the count a seed folds in, what a position has already shown. This is the
 * same state in one piece, for the two callers that need all of it at once: an implementation
 * writing a player's state in from somewhere else, and an admin surface reading out what is there.
 *
 * <p><b>One period, never two.</b> A pool holds exactly one period's state and a new period replaces
 * the record wholesale, which is what makes a rotation rollover need no sweep. So the period is a
 * field here rather than part of a key: a state answered for a period that has passed is simply not
 * this one.
 *
 * <p>Immutable, and every map is copied on the way in and handed out unmodifiable, so a caller
 * cannot reach back into a store's own bookkeeping through a record it was given.
 */
public record RerollState(long period, int spent, @Nonnull Map<Integer, String> overrides,
                          @Nonnull Map<Integer, Integer> counts,
                          @Nonnull Map<Integer, Set<String>> seen) {

    public RerollState {
        overrides = copyStrings(overrides);
        counts = copyCounts(counts);
        seen = copySeen(seen);
        spent = Math.max(0, spent);
    }

    /** A pool nobody has re-rolled in this period: no overrides, no counts, nothing seen. */
    @Nonnull
    public static RerollState none(long period) {
        return new RerollState(period, 0, Map.of(), Map.of(), Map.of());
    }

    /** True when this state records nothing at all, so writing it is the same as clearing. */
    public boolean isEmpty() {
        return spent == 0 && overrides.isEmpty() && counts.isEmpty() && seen.isEmpty();
    }

    /** What is shown at {@code position} instead of the base draw's pick, or null. */
    @Nullable
    public String overrideAt(int position) {
        return overrides.get(Integer.valueOf(position));
    }

    /** How often {@code position} has been re-rolled this period. */
    public int countAt(int position) {
        Integer count = counts.get(Integer.valueOf(position));
        return count == null ? 0 : count.intValue();
    }

    /** Every id {@code position} has already shown this period. Never null. */
    @Nonnull
    public Set<String> seenAt(int position) {
        Set<String> ids = seen.get(Integer.valueOf(position));
        return ids == null ? Set.of() : ids;
    }

    @Nonnull
    private static Map<Integer, String> copyStrings(@Nullable Map<Integer, String> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<Integer, String> out = new HashMap<>();
        for (Map.Entry<Integer, String> entry : source.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null && !entry.getValue().isBlank()) {
                out.put(entry.getKey(), entry.getValue());
            }
        }
        return Collections.unmodifiableMap(out);
    }

    @Nonnull
    private static Map<Integer, Integer> copyCounts(@Nullable Map<Integer, Integer> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<Integer, Integer> out = new HashMap<>();
        for (Map.Entry<Integer, Integer> entry : source.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null && entry.getValue().intValue() > 0) {
                out.put(entry.getKey(), entry.getValue());
            }
        }
        return Collections.unmodifiableMap(out);
    }

    @Nonnull
    private static Map<Integer, Set<String>> copySeen(@Nullable Map<Integer, Set<String>> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<Integer, Set<String>> out = new HashMap<>();
        for (Map.Entry<Integer, Set<String>> entry : source.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null || entry.getValue().isEmpty()) {
                continue;
            }
            Set<String> ids = new HashSet<>();
            for (String id : entry.getValue()) {
                if (id != null && !id.isBlank()) {
                    ids.add(id);
                }
            }
            if (!ids.isEmpty()) {
                out.put(entry.getKey(), Collections.unmodifiableSet(ids));
            }
        }
        return Collections.unmodifiableMap(out);
    }
}
