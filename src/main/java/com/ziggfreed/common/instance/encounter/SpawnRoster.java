package com.ziggfreed.common.instance.encounter;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Random;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * A generic, deterministic weighted spawn-roster planner - the mod-agnostic lift of Kweebec's
 * {@code AiHunterController.planRoster} + {@code weightedPick} + {@code addArchetype}, generalized over a
 * unit type {@code T}. PURE LOGIC: no engine API, no ids, no config - the consumer supplies the
 * {@link SpawnUnit}s (weight, spawnTier, count) and asks for a plan at a tier with a cap and a seed.
 *
 * <p><b>Determinism.</b> Every method takes the {@code seed} from the caller and drives a fresh
 * {@link Random}; nothing reads the wall clock or {@link Math#random()}. The same {@code (tier, cap, seed)}
 * always yields the same plan, so a round is reproducible off its world seed. Eligibility is gated by
 * {@code spawnTier <= tier}; the pick is weighted-with-replacement and honors each unit's {@code count}.
 *
 * <p>Immutable: the unit list is copied on construction. Safe to share and call from any thread (each call
 * builds its own {@link Random} and its own plan list).
 *
 * @param <T> the spawn-unit type
 */
public final class SpawnRoster<T> {

    @Nonnull
    private final List<SpawnUnit<T>> units;

    /** @param units the roster entries (copied; never null). */
    public SpawnRoster(@Nonnull Collection<SpawnUnit<T>> units) {
        this.units = List.copyOf(units);
    }

    /** The immutable roster entries. */
    @Nonnull
    public List<SpawnUnit<T>> units() {
        return units;
    }

    /**
     * Plan a roster of up to {@code cap} units eligible at {@code tier}: repeated weighted picks (with
     * replacement, each pick adding that unit's {@code count} copies) fill the plan to the cap.
     * Deterministic via {@code new Random(seed)}. Generalizes {@code AiHunterController.planRoster}'s
     * fill-loop (sans the rule-set primary seed, which is consumer policy - prepend it yourself).
     *
     * @param tier the current tier; only units with {@code spawnTier <= tier} are eligible
     * @param cap  the maximum plan size; a {@code cap <= 0} yields an empty plan
     * @param seed the deterministic seed for this plan
     * @return the planned units (never null; empty when nothing is eligible or {@code cap <= 0})
     */
    @Nonnull
    public List<T> planRoster(int tier, int cap, long seed) {
        List<T> plan = new ArrayList<>();
        if (cap <= 0) {
            return plan;
        }
        List<SpawnUnit<T>> eligible = eligible(tier);
        if (eligible.isEmpty()) {
            return plan;
        }
        Random rng = new Random(seed);
        int guard = 0;
        while (plan.size() < cap && guard++ < cap * 8) {
            SpawnUnit<T> pick = weightedPick(eligible, rng);
            if (pick == null) {
                break;
            }
            addUnit(plan, pick, cap);
        }
        return plan;
    }

    /**
     * A single eligible weighted pick at {@code tier}, deterministic via {@code new Random(seed)}.
     *
     * @return the picked unit value, or {@code null} when nothing is eligible at {@code tier}
     */
    @Nullable
    public T weightedPick(int tier, long seed) {
        List<SpawnUnit<T>> eligible = eligible(tier);
        SpawnUnit<T> pick = weightedPick(eligible, new Random(seed));
        return pick == null ? null : pick.unit();
    }

    /** The units eligible at {@code tier} ({@code spawnTier <= tier}). */
    @Nonnull
    private List<SpawnUnit<T>> eligible(int tier) {
        List<SpawnUnit<T>> out = new ArrayList<>();
        for (SpawnUnit<T> u : units) {
            if (u.eligibleAt(tier)) {
                out.add(u);
            }
        }
        return out;
    }

    /** Add a unit's {@code count} copies to the plan, never exceeding {@code cap}. */
    private static <T> void addUnit(@Nonnull List<T> plan, @Nonnull SpawnUnit<T> u, int cap) {
        int copies = u.safeCount();
        for (int i = 0; i < copies && plan.size() < cap; i++) {
            plan.add(u.unit());
        }
    }

    /**
     * Weighted pick over the eligible units; {@code null} only when the list is empty. When every weight
     * is {@code <= 0} the pick is uniform (mirrors Kweebec's {@code weightedPick} zero-total fallback).
     */
    @Nullable
    private static <T> SpawnUnit<T> weightedPick(@Nonnull List<SpawnUnit<T>> eligible, @Nonnull Random rng) {
        if (eligible.isEmpty()) {
            return null;
        }
        double total = 0.0;
        for (SpawnUnit<T> u : eligible) {
            total += u.safeWeight();
        }
        if (total <= 0.0) {
            return eligible.get(rng.nextInt(eligible.size()));
        }
        double r = rng.nextDouble() * total;
        for (SpawnUnit<T> u : eligible) {
            r -= u.safeWeight();
            if (r <= 0.0) {
                return u;
            }
        }
        return eligible.get(eligible.size() - 1);
    }
}
