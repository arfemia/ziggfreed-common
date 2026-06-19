package com.ziggfreed.common.instance.encounter;

import javax.annotation.Nonnull;

/**
 * One weighted entry in a {@link SpawnRoster}: a generic unit value plus the policy a roster planner
 * needs to pick and place it. PURE DATA, mod-agnostic - {@code T} is whatever the consumer keys a spawn
 * on (an archetype asset, a role id String, an enum). The generalized lift of Kweebec's hunter archetype
 * fields ({@code weight()}/{@code spawnTier()}/{@code count()}) onto a generic record.
 *
 * @param unit      the consumer's spawn unit (never null)
 * @param weight    relative pick weight; clamped to {@code >= 0} by the roster (0 = never picked unless
 *                  it is the only eligible unit and every weight is 0, the uniform-fallback case)
 * @param spawnTier the minimum tier at which this unit becomes eligible (lower = available earlier)
 * @param count     how many copies one pick of this unit adds to a plan; treated as {@code max(1, count)}
 * @param <T>       the spawn-unit type
 */
public record SpawnUnit<T>(@Nonnull T unit, double weight, int spawnTier, int count) {

    /** A unit with {@code count = 1}. */
    @Nonnull
    public static <T> SpawnUnit<T> of(@Nonnull T unit, double weight, int spawnTier) {
        return new SpawnUnit<>(unit, weight, spawnTier, 1);
    }

    /** True if this unit is eligible at the given tier ({@code spawnTier <= tier}). */
    public boolean eligibleAt(int tier) {
        return spawnTier <= tier;
    }

    /** Pick weight clamped to a non-negative value (a negative authored weight reads as 0). */
    public double safeWeight() {
        return Math.max(0.0, weight);
    }

    /** Copies one pick adds, at least 1 (a 0 / negative authored count reads as 1). */
    public int safeCount() {
        return Math.max(1, count);
    }
}
