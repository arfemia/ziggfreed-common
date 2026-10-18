package com.ziggfreed.common.track.gain;

import javax.annotation.Nonnull;

import com.ziggfreed.common.track.GainCall;
import com.ziggfreed.common.track.TrackDef;
import com.ziggfreed.common.track.TrackRecord;
import com.ziggfreed.common.track.asset.TrackAsset;

/**
 * The {@value GainFactors#RESTED} factor: a track's rested pool, spent on top of a gain while it lasts. Off on every
 * track until its {@code Gain.Rested} group is written (R572), so it compiles to {@link GainStep#NONE} everywhere
 * else. Where it is on, a gain of {@code a} pays {@code a + min(pool, a * (Multiplier - 1))}, the extra taken from
 * the pool (rounded up, as the MMO's pool spends), the way the MMO's rested XP has always paid: on top of every
 * multiplier before it, never compounding with them. The pool fills while the player is away
 * ({@link #fillFor}).
 */
public final class RestedFactor implements GainFactor {

    public static final RestedFactor INSTANCE = new RestedFactor();

    private RestedFactor() {
    }

    @Nonnull
    @Override
    public GainStep compile(@Nonnull GainScope scope) {
        TrackAsset.Rested rested = scope.def().asset().rested();
        if (rested == null || rested.multiplier() <= 1.0) {
            return GainStep.NONE;
        }
        double extra = rested.multiplier() - 1.0;
        return (call, amount) -> spend(call, amount, extra);
    }

    /** {@code amount} with the pool's extra on top, the spent pool written back to the call's record. */
    private static double spend(@Nonnull GainCall call, double amount, double extra) {
        TrackRecord record = call.record();
        long pool = record.rested();
        if (amount <= 0.0 || pool <= 0L) {
            return amount;
        }
        double bonus = Math.min((double) pool, amount * extra);
        if (bonus <= 0.0) {
            return amount;
        }
        call.record(record.withRested(pool - (long) Math.ceil(bonus)));
        return amount + bonus;
    }

    /**
     * How much {@code record}'s pool holds after the player was away {@code awayMs}, under {@code rested}: nothing
     * added for a time away below its {@code MinOfflineMinutes}, the whole time (up to {@code MaxOfflineHours})
     * at or past it, {@code PerHour} plus {@code PercentPerHour} of the next level an hour, held under
     * {@code MaxPool}. Pure.
     */
    public static long fillFor(@Nonnull TrackDef def, @Nonnull TrackRecord record, @Nonnull TrackAsset.Rested rested,
            long awayMs) {
        long pool = record.rested();
        if (awayMs < rested.minOfflineMinutes() * 60_000L) {
            return pool;
        }
        double hours = Math.min(awayMs / 3_600_000.0, (double) rested.maxOfflineHours());
        int level = def.level(record);
        long next = def.table().floorOf(level + 1);
        // At the top of a curve with no Beyond there is no next level, so the percent fills nothing.
        long nextLevel = next == Long.MAX_VALUE ? 0L : Math.max(0L, next - def.table().floorOf(level));
        double perHour = rested.perHour() + rested.percentPerHour() / 100.0 * nextLevel;
        double added = perHour * hours;
        if (!(added > 0.0)) {
            return pool;
        }
        double filled = Math.min((double) rested.maxPool(), pool + added);
        return Math.max(pool, (long) filled);
    }
}
