package com.ziggfreed.common.instance.dungeon.omen;

import java.time.DayOfWeek;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.annotation.Nonnull;

import com.ziggfreed.common.instance.dungeon.layout.SplitMix64;
import com.ziggfreed.common.util.PeriodMath;

/**
 * Which omens a run meets, pure: the week's set, drawn from a dungeon's pool by the week alone, so every tier sharing
 * the pool shares the week's set and every server agrees on it; then the run's own, {@code Omens.Count} of that set
 * drawn by the run's seed, so a daily run (one seed for the day) gives every party the same ones. No engine type.
 *
 * <p>A week is the library's week, from Monday 00:00 UTC ({@link PeriodMath}), the window a dungeon's
 * {@code Rewards.Diminish} counts {@code Weekly} clears in.
 */
public final class OmenDraw {

    /** How far a week's boundary is shifted from the epoch grid so it falls on a Monday. */
    public static final long WEEK_ANCHOR_MS = PeriodMath.weekdayAnchorMs(DayOfWeek.MONDAY);

    /** The stream the week's set is drawn on. */
    private static final long WEEK_STREAM = 0x0DE7_3EEAL;

    /** The stream a run's draw is drawn on, apart from every stream its layout draws. */
    private static final long RUN_STREAM = 0x0DE7_2B7EL;

    private OmenDraw() {
    }

    /** The week {@code nowMs} falls in: whole weeks, each from Monday 00:00 UTC. */
    public static long weekOf(long nowMs) {
        return PeriodMath.periodIndex(PeriodMath.WEEK_MS, WEEK_ANCHOR_MS, nowMs);
    }

    /** When the week {@code nowMs} falls in ends, the moment the next week's set takes over, in epoch milliseconds. */
    public static long weekEndsAt(long nowMs) {
        return PeriodMath.nextBoundaryMs(PeriodMath.WEEK_MS, WEEK_ANCHOR_MS, nowMs);
    }

    /**
     * Week {@code week}'s set: {@code perWeek} omens of {@code pool} (ids matched without regard to case, blanks and
     * repeats dropped), in the pool's id order; the whole pool when {@code perWeek} is 0 or less or not below its size.
     */
    @Nonnull
    public static List<String> weekSet(@Nonnull Collection<String> pool, int perWeek, long week) {
        List<String> ids = distinctSorted(pool);
        if (perWeek <= 0 || perWeek >= ids.size()) {
            return ids;
        }
        long poolKey = String.join("\n", folded(ids)).hashCode();
        return pick(ids, perWeek, SplitMix64.derive(SplitMix64.mix(week) ^ SplitMix64.mix(poolKey), WEEK_STREAM));
    }

    /**
     * A run's omens: {@code count} of {@code weekSet} drawn by the run's {@code seed}, in the set's order; none for a
     * count of 0 or less, the whole set for one not below its size.
     */
    @Nonnull
    public static List<String> runDraw(@Nonnull List<String> weekSet, int count, long seed) {
        if (count <= 0 || weekSet.isEmpty()) {
            return List.of();
        }
        if (count >= weekSet.size()) {
            return List.copyOf(weekSet);
        }
        return pick(weekSet, count, SplitMix64.derive(seed, RUN_STREAM));
    }

    /** {@code count} of {@code ids} with no repeat, drawn from {@code random}, kept in the order of {@code ids}. */
    @Nonnull
    private static List<String> pick(@Nonnull List<String> ids, int count, @Nonnull SplitMix64 random) {
        List<Integer> order = new ArrayList<>(ids.size());
        for (int i = 0; i < ids.size(); i++) {
            order.add(i);
        }
        for (int i = 0; i < count; i++) {
            int j = i + random.nextInt(order.size() - i);
            Integer swap = order.get(i);
            order.set(i, order.get(j));
            order.set(j, swap);
        }
        List<Integer> chosen = new ArrayList<>(order.subList(0, count));
        chosen.sort(Comparator.naturalOrder());
        List<String> out = new ArrayList<>(count);
        for (int index : chosen) {
            out.add(ids.get(index));
        }
        return List.copyOf(out);
    }

    /** {@code pool}'s ids trimmed, blanks and case-blind repeats dropped (the first spelling kept), by folded id. */
    @Nonnull
    private static List<String> distinctSorted(@Nonnull Collection<String> pool) {
        Map<String, String> byFolded = new LinkedHashMap<>();
        for (String id : pool) {
            if (id != null && !id.isBlank()) {
                byFolded.putIfAbsent(id.trim().toLowerCase(Locale.ROOT), id.trim());
            }
        }
        List<String> keys = new ArrayList<>(byFolded.keySet());
        keys.sort(Comparator.naturalOrder());
        List<String> out = new ArrayList<>(keys.size());
        for (String key : keys) {
            out.add(byFolded.get(key));
        }
        return List.copyOf(out);
    }

    @Nonnull
    private static List<String> folded(@Nonnull List<String> ids) {
        List<String> out = new ArrayList<>(ids.size());
        for (String id : ids) {
            out.add(id.toLowerCase(Locale.ROOT));
        }
        return out;
    }
}
