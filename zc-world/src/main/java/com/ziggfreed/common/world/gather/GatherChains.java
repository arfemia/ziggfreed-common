package com.ziggfreed.common.world.gather;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Each player's break chain, which the {@code GatherRhythm} stat reads: a break within {@link #WINDOW_MS} of the last
 * one adds a step, up to {@link #MAX_STEPS}, and a longer pause starts the chain again. In memory only (a chain lasts
 * seconds, so a restart costs nothing), pruned as it grows. Thread-safe; the clock is passed in, so the rule is pure
 * to test.
 */
public final class GatherChains {

    /** How long after one break the next still continues the chain. */
    public static final long WINDOW_MS = 2_000L;

    /** The most steps a chain counts. */
    public static final int MAX_STEPS = 5;

    /** Past this many players held, chains older than the window are swept. */
    private static final int SWEEP_AT = 512;

    private static final Map<UUID, long[]> CHAINS = new ConcurrentHashMap<>();

    private GatherChains() {
    }

    /** {@code playerId} broke a block at {@code nowMs}: the chain steps on, or starts again after a pause. */
    public static void broke(@Nullable UUID playerId, long nowMs) {
        if (playerId == null) {
            return;
        }
        CHAINS.compute(playerId, (id, chain) -> next(chain, nowMs));
        if (CHAINS.size() > SWEEP_AT) {
            CHAINS.values().removeIf(chain -> nowMs - chain[0] > WINDOW_MS);
        }
    }

    /** The steps {@code playerId}'s chain counts at {@code nowMs}: 0 once the window since their last break passed. */
    public static int steps(@Nullable UUID playerId, long nowMs) {
        long[] chain = playerId == null ? null : CHAINS.get(playerId);
        return steps(chain, nowMs);
    }

    /** Forget a player's chain (they left). */
    public static void clear(@Nullable UUID playerId) {
        if (playerId != null) {
            CHAINS.remove(playerId);
        }
    }

    /** The chain {@code {lastBreakMs, steps}} after a break at {@code nowMs}. Pure. */
    @Nonnull
    static long[] next(@Nullable long[] chain, long nowMs) {
        if (chain == null || nowMs - chain[0] > WINDOW_MS) {
            return new long[] {nowMs, 0L};
        }
        return new long[] {nowMs, Math.min(MAX_STEPS, chain[1] + 1)};
    }

    /** The steps a chain counts at {@code nowMs}. Pure. */
    static int steps(@Nullable long[] chain, long nowMs) {
        if (chain == null || nowMs - chain[0] > WINDOW_MS) {
            return 0;
        }
        return (int) Math.min(MAX_STEPS, chain[1]);
    }
}
