package com.ziggfreed.common.track;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import javax.annotation.Nonnull;

/**
 * One climb as it is kept, immutable: what {@link TrackStore} reads and writes for one {@link TrackKey}.
 *
 * @param value    the current climb's whole value; a prestige starts it again
 * @param fraction what a gain left over below one whole point, carried into the next gain so fractional
 *                 gains add up exactly (the accumulator the MMO keeps per skill)
 * @param best     the best level this climb ever reached, 0 when nothing is on record (it then reads as the
 *                 current level); never lowered, by a prestige or anything else
 * @param prestige how many times this climb was started again
 * @param paid     per unlock row, by the row's key ({@code TrackAsset.Unlock#key}, never its place in the list),
 *                 the level that row has paid through; a row the map does not hold has paid nothing
 * @param rested   the rested pool: extra the track's {@code Gain.Rested} factor may still pay on top of gains,
 *                 filled while the player is away; 0 for none
 * @param picks    the picks made on this climb ({@link PickState}); owed picks are derived, never kept
 */
public record TrackRecord(long value, double fraction, int best, int prestige, @Nonnull Map<String, Integer> paid,
                          long rested, @Nonnull PickState picks) {

    /** Nothing on record: what a player who never touched a track holds. */
    public static final TrackRecord EMPTY = new TrackRecord(0L, 0.0, 0, 0, Map.of(), 0L, PickState.EMPTY);

    public TrackRecord {
        best = Math.max(0, best);
        prestige = Math.max(0, prestige);
        paid = marks(paid);
        rested = Math.max(0L, rested);
        picks = picks == null ? PickState.EMPTY : picks;
        if (!Double.isFinite(fraction)) {
            fraction = 0.0;
        }
    }

    /** A record with no picks. */
    public TrackRecord(long value, double fraction, int best, int prestige, @Nonnull Map<String, Integer> paid,
            long rested) {
        this(value, fraction, best, prestige, paid, rested, PickState.EMPTY);
    }

    /** A record with no rested pool and no picks. */
    public TrackRecord(long value, double fraction, int best, int prestige, @Nonnull Map<String, Integer> paid) {
        this(value, fraction, best, prestige, paid, 0L, PickState.EMPTY);
    }

    /** The level the unlock row keyed {@code row} has paid through, 0 for none. */
    public int paidThrough(@Nonnull String row) {
        Integer level = paid.get(row);
        return level == null ? 0 : level;
    }

    /** This record holding {@code value} and {@code fraction} instead. */
    @Nonnull
    public TrackRecord withValue(long value, double fraction) {
        return new TrackRecord(value, fraction, best, prestige, paid, rested, picks);
    }

    /** This record with its best level raised to {@code level}, never lowered. */
    @Nonnull
    public TrackRecord withBest(int level) {
        return level <= best ? this : new TrackRecord(value, fraction, level, prestige, paid, rested, picks);
    }

    /** This record with its prestige count at {@code count}. */
    @Nonnull
    public TrackRecord withPrestige(int count) {
        return new TrackRecord(value, fraction, best, count, paid, rested, picks);
    }

    /** This record with its rested pool at {@code pool} (0 or more). */
    @Nonnull
    public TrackRecord withRested(long pool) {
        return Math.max(0L, pool) == rested ? this
                : new TrackRecord(value, fraction, best, prestige, paid, pool, picks);
    }

    /** This record holding {@code state} as its picks. */
    @Nonnull
    public TrackRecord withPicks(@Nonnull PickState state) {
        return state.equals(picks) ? this : new TrackRecord(value, fraction, best, prestige, paid, rested, state);
    }

    /** This record with the row keyed {@code row} paid through {@code level}, never lowered. */
    @Nonnull
    public TrackRecord withPaid(@Nonnull String row, int level) {
        return level <= paidThrough(row) ? this : withPaidAt(row, level);
    }

    /**
     * This record with the row keyed {@code row} paid through exactly {@code level}, lowered if need be (0 for
     * nothing): how a pass puts back a mark whose Claim could not be written, so the next pass writes it.
     */
    @Nonnull
    public TrackRecord withPaidAt(@Nonnull String row, int level) {
        if (paidThrough(row) == Math.max(0, level)) {
            return this;
        }
        Map<String, Integer> next = new HashMap<>(paid);
        next.put(row, level);
        return new TrackRecord(value, fraction, best, prestige, next, rested, picks);
    }

    /** This record with each row {@code rows} keys paid through at least {@code level}. */
    @Nonnull
    public TrackRecord withAllPaidThrough(@Nonnull Collection<String> rows, int level) {
        TrackRecord out = this;
        for (String row : rows) {
            out = out.withPaid(row, level);
        }
        return out;
    }

    /** Is this record the same as nothing on record? */
    public boolean isEmpty() {
        return value == 0L && fraction == 0.0 && best == 0 && prestige == 0 && paid.isEmpty() && rested == 0L
                && picks.isEmpty();
    }

    /** {@code paid} as a record keeps it: blank keys and marks of nothing dropped, so equal records compare equal. */
    @Nonnull
    private static Map<String, Integer> marks(@Nonnull Map<String, Integer> paid) {
        if (paid.isEmpty()) {
            return Map.of();
        }
        Map<String, Integer> kept = new HashMap<>();
        for (Map.Entry<String, Integer> mark : paid.entrySet()) {
            String row = mark.getKey();
            Integer level = mark.getValue();
            if (row != null && !row.isBlank() && level != null && level > 0) {
                kept.put(row, level);
            }
        }
        return Map.copyOf(kept);
    }
}
