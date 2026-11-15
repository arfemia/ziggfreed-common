package com.ziggfreed.common.instance.dungeon.layout;

import javax.annotation.Nonnull;

/**
 * The layout's own random stream: SplitMix64 (Steele, Lea and Flood), so a seed gives the same layout on every JVM
 * and every engine version, whatever {@code java.util.Random} does. A stream {@link #derive derived} from a seed and
 * a stream number is independent of every other, which is how each beat keeps its own: a branch drawing numbers
 * never shifts the path's.
 */
public final class SplitMix64 {

    private static final long GOLDEN_GAMMA = 0x9E3779B97F4A7C15L;

    private long state;

    public SplitMix64(long seed) {
        this.state = seed;
    }

    /** A stream of its own for {@code stream} under {@code seed}. */
    @Nonnull
    public static SplitMix64 derive(long seed, long stream) {
        return new SplitMix64(mix(seed ^ mix(stream * GOLDEN_GAMMA + GOLDEN_GAMMA)));
    }

    /** SplitMix64's finalizer: every bit of {@code z} stirred into every bit of the result. */
    public static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    public long nextLong() {
        state += GOLDEN_GAMMA;
        return mix(state);
    }

    /** A number from 0 to {@code bound} less one; 0 when {@code bound} is 1 or less. */
    public int nextInt(int bound) {
        if (bound <= 1) {
            return 0;
        }
        return (int) (((nextLong() >>> 32) * bound) >>> 32);
    }

    /** A number from 0 inclusive to 1 exclusive. */
    public double nextDouble() {
        return (nextLong() >>> 11) * 0x1.0p-53;
    }
}
