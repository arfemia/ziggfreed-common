package com.ziggfreed.common.track.curve;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * One curve, tabulated: the value each level starts at, the levels past the last piece ({@code Beyond}),
 * and the named tiers over its levels. Pure: built from plain pieces, never from an asset, so a test (or the
 * MMO's check against its own curve) builds one directly.
 *
 * <p><b>Pieces, in order.</b> Each piece covers the levels after the last piece's end:
 * <ul>
 *   <li>a {@code Floors} piece lists the value each of its levels starts at, absolute and ascending (a value
 *       below the level before it is raised to that level's, so the table never falls). A first piece's first
 *       floor is level 1's, which may be below 0 (a standing that can be lost);</li>
 *   <li>a {@code Hybrid} piece runs to its {@code To} level and costs each level what the hybrid formula says a
 *       level of that number costs: the OSRS sum {@code floor(l + Scale * 2^(l/7))} over the levels below,
 *       quartered, plus {@code Base * level * (level - 1) / 2}. Its step from one level to the next is added to
 *       where the piece before it ended, so a first Hybrid piece is the formula exactly (level 1 at 0), down to
 *       the last bit of the double arithmetic, which is what lets a shipped curve match a consumer's own table
 *       level for level.</li>
 * </ul>
 * A piece with neither, or a Hybrid piece with no {@code To}, adds nothing. At most {@link #MAX_LEVELS} levels
 * are tabulated; a value too large for a {@code long} saturates.
 *
 * <p><b>Beyond.</b> Past the last tabulated level, every {@code Every} more value is one more level, without
 * end (a track's own {@code Cap} stops it). Without it, the last tabulated level is the top.
 *
 * <p><b>Tiers</b> name level ranges: a tier holds from its {@code From} level to the next tier's.
 */
public final class CurveTable {

    /** The most levels one curve tabulates. */
    public static final int MAX_LEVELS = 10_000;

    /**
     * One piece as written: a hybrid one ({@code base} or {@code scale} set, with {@code to}) or a floors one
     * ({@code floors} non-empty, which wins).
     */
    public record Piece(@Nullable Double base, @Nullable Double scale, @Nullable long[] floors, @Nullable Integer to) {

        /** A Hybrid piece to level {@code to}. */
        @Nonnull
        public static Piece hybrid(double base, double scale, int to) {
            return new Piece(base, scale, null, to);
        }

        /** A Floors piece. */
        @Nonnull
        public static Piece floors(@Nonnull long... floors) {
            return new Piece(null, null, floors.clone(), null);
        }

        boolean isFloors() {
            return floors != null && floors.length > 0;
        }

        boolean isHybrid() {
            return !isFloors() && (base != null || scale != null);
        }
    }

    /**
     * One named range of levels.
     *
     * @param id      the tier's id, as authored
     * @param from    the first level it holds
     * @param nameKey the lang key it is called by, or null (named by its id)
     */
    public record Tier(@Nonnull String id, int from, @Nullable String nameKey) {
    }

    /** {@code floors[level]} is the value {@code level} starts at, for 1..top; index 0 repeats level 1. */
    private final long[] floors;
    private final int top;
    private final long every;
    private final List<Tier> tiers;

    private CurveTable(@Nonnull long[] floors, int top, long every, @Nonnull List<Tier> tiers) {
        this.floors = floors;
        this.top = top;
        this.every = every;
        this.tiers = tiers;
    }

    /**
     * Tabulate {@code pieces} in order, with {@code every} the Beyond step (0 or less: none) and {@code tiers}
     * the named ranges (sorted here by their first level, then id).
     */
    @Nonnull
    public static CurveTable build(@Nonnull List<Piece> pieces, long every, @Nonnull List<Tier> tiers) {
        long[] buf = new long[MAX_LEVELS + 1];
        int top = 0;
        for (Piece piece : pieces) {
            if (piece == null || top >= MAX_LEVELS) {
                continue;
            }
            if (piece.isFloors()) {
                for (long floor : piece.floors()) {
                    if (top >= MAX_LEVELS) {
                        break;
                    }
                    top++;
                    buf[top] = top > 1 ? Math.max(floor, buf[top - 1]) : floor;
                }
            } else if (piece.isHybrid() && piece.to() != null) {
                top = hybrid(buf, top, piece.base() == null ? 0.0 : piece.base(),
                        piece.scale() == null ? 0.0 : piece.scale(), Math.min(piece.to(), MAX_LEVELS));
            }
        }
        if (top == 0) {
            top = 1;
            buf[1] = 0L;
        }
        buf[0] = buf[1];
        long[] floors = new long[top + 1];
        System.arraycopy(buf, 0, floors, 0, top + 1);
        List<Tier> sorted = new ArrayList<>();
        for (Tier tier : tiers) {
            if (tier != null && !tier.id().isBlank()) {
                sorted.add(tier);
            }
        }
        sorted.sort(Comparator.comparingInt(Tier::from).thenComparing(t -> t.id().toLowerCase(Locale.ROOT)));
        return new CurveTable(floors, top, Math.max(0L, every), List.copyOf(sorted));
    }

    /**
     * Add a Hybrid piece's levels after {@code top} up to {@code to}, each the formula's own step at that level
     * added to the level before it. The OSRS sum runs from level 2 exactly as the formula's own table does,
     * so a first piece is the formula itself. Returns the new top.
     */
    private static int hybrid(@Nonnull long[] buf, int top, double base, double scale, int to) {
        if (to <= top) {
            return top;
        }
        if (top == 0) {
            top = 1;
            buf[1] = 0L;
        }
        double osrs = 0;
        long previous = 0L;
        for (int level = 2; level <= to; level++) {
            osrs += Math.floor((level - 1) + scale * Math.pow(2, (level - 1) / 7.0));
            long cost = (long) (osrs / 4.0 + base * (double) level * (level - 1) / 2.0);
            if (level > top) {
                long step = cost - previous;
                buf[level] = saturatingAdd(buf[level - 1], step < 0 ? Long.MAX_VALUE : step);
                top = level;
            }
            previous = cost;
        }
        return top;
    }

    /** The last tabulated level. */
    public int top() {
        return top;
    }

    /** The Beyond step past the top, 0 for none. */
    public long every() {
        return every;
    }

    /** The value level 1 starts at: the least a climb holds. */
    public long minimum() {
        return floors[1];
    }

    /**
     * The value {@code level} starts at: level 1's for anything at or below 1; past the top, the top's plus a
     * Beyond step a level (saturating), or {@link Long#MAX_VALUE} (never reached) with no Beyond.
     */
    public long floorOf(int level) {
        if (level <= 1) {
            return floors[1];
        }
        if (level <= top) {
            return floors[level];
        }
        if (every <= 0) {
            return Long.MAX_VALUE;
        }
        return saturatingAdd(floors[top], saturatingMultiply(level - (long) top, every));
    }

    /**
     * The level {@code value} has reached: the highest level whose floor it meets, 1 below level 1's floor;
     * past the top, the top plus one level per whole Beyond step, or the top with no Beyond.
     */
    public int levelOf(long value) {
        if (value < floors[1]) {
            return 1;
        }
        if (value >= floors[top]) {
            if (every <= 0) {
                return top;
            }
            long past = (value - floors[top]) / every;
            return (int) Math.min(Integer.MAX_VALUE, top + past);
        }
        int low = 1;
        int high = top;
        while (low < high) {
            int mid = (low + high + 1) >>> 1;
            if (floors[mid] <= value) {
                low = mid;
            } else {
                high = mid - 1;
            }
        }
        return low;
    }

    /**
     * The most a climb holds under {@code cap} (null: none): the cap level's floor, held to the top's floor
     * when the curve has no Beyond; with no cap, the top's floor, or no ceiling at all with a Beyond.
     */
    public long ceiling(@Nullable Integer cap) {
        if (cap == null) {
            return every > 0 ? Long.MAX_VALUE : floors[top];
        }
        int level = Math.max(1, cap);
        return every > 0 ? floorOf(level) : floors[Math.min(level, top)];
    }

    /** The named tiers, by their first level. */
    @Nonnull
    public List<Tier> tiers() {
        return tiers;
    }

    /** The tier {@code level} stands in (the last one starting at or below it), or null when none does. */
    @Nullable
    public Tier tierOf(int level) {
        Tier held = null;
        for (Tier tier : tiers) {
            if (tier.from() <= level) {
                held = tier;
            }
        }
        return held;
    }

    /** Every tier {@code level} has reached (each starting at or below it), lowest first. */
    @Nonnull
    public List<Tier> tiersReached(int level) {
        List<Tier> out = new ArrayList<>();
        for (Tier tier : tiers) {
            if (tier.from() <= level) {
                out.add(tier);
            }
        }
        return out;
    }

    /** The tier {@code id} names, matched without regard to case, or null. */
    @Nullable
    public Tier tier(@Nullable String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        String wanted = id.trim();
        for (Tier tier : tiers) {
            if (tier.id().equalsIgnoreCase(wanted)) {
                return tier;
            }
        }
        return null;
    }

    /** {@code a + b}, held at {@link Long#MAX_VALUE} or {@link Long#MIN_VALUE} instead of wrapping. */
    public static long saturatingAdd(long a, long b) {
        long sum = a + b;
        if (((a ^ sum) & (b ^ sum)) < 0) {
            return sum < 0 ? Long.MAX_VALUE : Long.MIN_VALUE;
        }
        return sum;
    }

    static long saturatingMultiply(long a, long b) {
        long high = Math.multiplyHigh(a, b);
        long low = a * b;
        if ((high == 0 && low >= 0) || (high == -1 && low < 0)) {
            return low;
        }
        return (a ^ b) < 0 ? Long.MIN_VALUE : Long.MAX_VALUE;
    }
}
