package com.ziggfreed.common.util;

/**
 * A deterministic SplitMix64 pseudo-random generator - a mod-agnostic seeded stream for any path that needs
 * REPRODUCIBLE randomness (a chunk-reload-stable roll, a cross-server-stable rotation) and so cannot use
 * {@code java.util.Random} (whose seeding + stream are not stable across the guarantees such paths need).
 * Given the same seed it yields the identical stream. Seed from stable inputs (an entity UUID folded with a
 * world seed, a role-name hash, a rotation epoch) via {@link #mix} so the roll survives a restart.
 *
 * <p>Not thread-safe (each roll builds its own instance); pure arithmetic, zero engine coupling.
 *
 * <p>Lifted from the MMO mob-scaling mod (its local copy is deleted; consumers import this one). Do NOT
 * repoint an already-shipped deterministic stream at {@link #mix} unless it was bit-identical: a 3-input fold
 * with an extra constant (as in {@code StructureGiverSpawnSystem.deterministicRoll}) is NOT reproducible by
 * {@code mix(mix(a,b),c)} and swapping it changes the stream.
 */
public final class SplitMix64 {

    private static final long GOLDEN = 0x9E3779B97F4A7C15L;
    private static final long MIX1 = 0xBF58476D1CE4E5B9L;
    private static final long MIX2 = 0x94D049BB133111EBL;

    private long state;

    public SplitMix64(long seed) {
        this.state = seed;
    }

    /** The next 64-bit value in the deterministic stream. */
    public long nextLong() {
        long z = (state += GOLDEN);
        z = (z ^ (z >>> 30)) * MIX1;
        z = (z ^ (z >>> 27)) * MIX2;
        return z ^ (z >>> 31);
    }

    /** A double in {@code [0, 1)} (53-bit mantissa). */
    public double nextDouble() {
        return (nextLong() >>> 11) * 0x1.0p-53;
    }

    /** Deterministically fold two longs into one seed (stable across runs, unlike {@code Objects.hash}). */
    public static long mix(long a, long b) {
        long z = a * GOLDEN + b;
        z = (z ^ (z >>> 30)) * MIX1;
        z = (z ^ (z >>> 27)) * MIX2;
        return z ^ (z >>> 31);
    }
}
