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
 * Which omens a tier meets this week, pure, Mythic+ style (R1006): the week's set is a dungeon's pool put in an order
 * drawn by the week alone, cut to {@code Omens.PerWeek}, so every tier sharing the pool shares it and every server
 * agrees on it; a tier meets the first {@code Omens.Count} of that order ({@link #forTier}), so a tier's omens are the
 * same for every run of it that week, and a higher tier's include every lower tier's. No engine type.
 *
 * <p>A week is the library's week, from Monday 00:00 UTC ({@link PeriodMath}), the window a dungeon's
 * {@code Rewards.Diminish} counts {@code Weekly} clears in.
 */
public final class OmenDraw {

    /** How far a week's boundary is shifted from the epoch grid so it falls on a Monday. */
    public static final long WEEK_ANCHOR_MS = PeriodMath.weekdayAnchorMs(DayOfWeek.MONDAY);

    /** The stream the week's order is drawn on. */
    private static final long WEEK_STREAM = 0x0DE7_3EEAL;

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
     * Week {@code week}'s set, in its drawn order: every omen of {@code pool} (ids matched without regard to case,
     * blanks and repeats dropped) put in an order drawn by the week and the pool alone, then the first {@code perWeek}
     * of it; the whole order when {@code perWeek} is 0 or less or not below the pool's size.
     */
    @Nonnull
    public static List<String> weekSet(@Nonnull Collection<String> pool, int perWeek, long week) {
        List<String> ids = distinctSorted(pool);
        if (ids.size() < 2) {
            return ids;
        }
        long poolKey = String.join("\n", folded(ids)).hashCode();
        List<String> order = shuffled(ids, SplitMix64.derive(SplitMix64.mix(week) ^ SplitMix64.mix(poolKey),
                WEEK_STREAM));
        return perWeek <= 0 || perWeek >= order.size() ? order : List.copyOf(order.subList(0, perWeek));
    }

    /**
     * A tier's omens this week: the first {@code count} of {@code weekSet}, so a tier meeting more includes every omen
     * of one meeting fewer; none for a count of 0 or less, the whole set for one not below its size.
     */
    @Nonnull
    public static List<String> forTier(@Nonnull List<String> weekSet, int count) {
        if (count <= 0 || weekSet.isEmpty()) {
            return List.of();
        }
        return List.copyOf(weekSet.subList(0, Math.min(count, weekSet.size())));
    }

    /** {@code ids} in an order drawn from {@code random} (a Fisher-Yates shuffle of a copy). */
    @Nonnull
    private static List<String> shuffled(@Nonnull List<String> ids, @Nonnull SplitMix64 random) {
        List<String> out = new ArrayList<>(ids);
        for (int i = 0; i < out.size() - 1; i++) {
            int j = i + random.nextInt(out.size() - i);
            String swap = out.get(i);
            out.set(i, out.get(j));
            out.set(j, swap);
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
