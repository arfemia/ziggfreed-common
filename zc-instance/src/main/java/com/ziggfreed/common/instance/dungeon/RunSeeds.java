package com.ziggfreed.common.instance.dungeon;

import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.instance.dungeon.layout.SplitMix64;
import com.ziggfreed.common.util.PeriodMath;

/**
 * Where a run's seed comes from, the one source every part of a run reads it from: a generated run's layout and room
 * variants ({@code gen/GeneratedRuns}). A run's omens are its tier's for the week, never the seed's
 * ({@code omen/OmenDraw}).
 *
 * <p>A dungeon with {@code Seed.Daily} gives every run that day the one seed {@link #daily} makes from the day and the
 * dungeon's id, so every party that day walks the same rooms in the same order; any other run
 * takes a fresh random seed. A day is the library's day: midnight to midnight UTC, the window a dungeon's
 * {@code Rewards.Diminish} counts {@code Daily} clears in ({@link PeriodMath}), so the daily layout and the daily count
 * turn over together.
 *
 * <p>Pure but for the random seed: {@link #daily} and {@link #dayOf} are plain arithmetic, the same on every JVM.
 */
public final class RunSeeds {

    /** The stream a daily seed is drawn on, so it never equals the day's or the id's own stream of another use. */
    private static final long DAILY_STREAM = 0x5EEDDA11L;

    private RunSeeds() {
    }

    /** The library's day {@code nowMs} falls in: whole days since 1970-01-01, each from midnight UTC. */
    public static long dayOf(long nowMs) {
        return PeriodMath.periodIndex(PeriodMath.DAY_MS, 0L, nowMs);
    }

    /**
     * The seed every run of dungeon {@code dungeonId} (matched without regard to case or surrounding space) shares on
     * day {@code day} ({@link #dayOf}): the same for every call that day, another the next.
     */
    public static long daily(long day, @Nonnull String dungeonId) {
        long id = dungeonId.trim().toLowerCase(Locale.ROOT).hashCode();
        return SplitMix64.derive(SplitMix64.mix(day) ^ SplitMix64.mix(id + DAILY_STREAM), DAILY_STREAM).nextLong();
    }

    /**
     * The seed a run of {@code dungeon} starting at {@code nowMs} takes: its daily seed with {@code Seed.Daily}, else a
     * fresh random one (also for a dungeon no longer loaded).
     */
    public static long seedFor(@Nullable DungeonAsset dungeon, long nowMs) {
        String id = dungeon == null ? null : dungeon.getId();
        if (dungeon != null && dungeon.seedDaily() && id != null && !id.isBlank()) {
            return daily(dayOf(nowMs), id);
        }
        return ThreadLocalRandom.current().nextLong();
    }
}
