package com.ziggfreed.common.instance.reward;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * A generic, mod-agnostic score-tiered reward LOOT TABLE: a fixed {@link #guaranteed} list always granted
 * plus a weighted, score-gated {@link #pool} of {@link LootEntry} templates rolled by a player's run
 * score. The runtime form of a {@code LootTableAsset} (the codec face); the consumer resolves the table by
 * id from {@code LootTableConfig} and {@link #roll}s it at its reward choke-point with the player's score.
 *
 * <p><b>"Better loot for a better score"</b> works two ways: a higher {@code score} (a) makes more
 * {@code s}-gated premium entries ELIGIBLE and (b) earns BONUS ROLLS via {@link #scorePerBonusRoll}
 * (+1 roll per that many points), up to {@link #maxRolls}.
 *
 * <p><b>Determinism</b> is the contract (mirrors {@code instance/encounter/SpawnRoster}): {@link #roll}
 * takes the caller's {@link Random}, so the same seed yields the same loot - testable, and reproducible off
 * a round/player seed. The weighted-pick is with-replacement; a zero-total-weight eligible set falls back
 * to a uniform pick (the {@code SpawnRoster} convention). Immutable; safe to share and call from any thread.
 */
public record LootTable(@Nonnull List<InstanceReward> guaranteed, @Nonnull List<LootEntry> pool,
                        int rolls, int scorePerBonusRoll, int maxRolls) {

    public LootTable {
        guaranteed = List.copyOf(guaranteed);
        pool = List.copyOf(pool);
    }

    /**
     * Roll this table for a player with {@code score}: the guaranteed list, then up to
     * {@code clamp(rolls + score/scorePerBonusRoll, 0, maxRolls)} weighted picks among the pool entries
     * eligible at {@code score} ({@code minScore <= score}), each resolved to a concrete quantity.
     *
     * @param score the player's run score (the eligibility + bonus-roll driver); negative reads as 0
     * @param rng   the caller's RNG (one stream drives both the pick and each quantity roll)
     * @return guaranteed ++ rolled rewards (never null; the guaranteed list alone when nothing is eligible)
     */
    @Nonnull
    public List<InstanceReward> roll(int score, @Nonnull Random rng) {
        List<InstanceReward> out = new ArrayList<>(guaranteed);
        int s = Math.max(0, score);
        List<LootEntry> eligible = new ArrayList<>();
        for (LootEntry e : pool) {
            if (e.minScore() <= s) {
                eligible.add(e);
            }
        }
        if (eligible.isEmpty()) {
            return out;
        }
        int n = rolls;
        if (scorePerBonusRoll > 0) {
            n += s / scorePerBonusRoll;
        }
        if (maxRolls > 0) {
            n = Math.min(n, maxRolls);
        }
        for (int i = 0; i < n; i++) {
            LootEntry pick = weightedPick(eligible, rng);
            if (pick == null) {
                break;
            }
            out.add(pick.resolve(rng));
        }
        return out;
    }

    /**
     * Weighted pick over the eligible entries; {@code null} only when the list is empty. When every weight
     * is {@code <= 0} the pick is uniform (mirrors {@code SpawnRoster}'s zero-total fallback).
     */
    @Nullable
    private static LootEntry weightedPick(@Nonnull List<LootEntry> eligible, @Nonnull Random rng) {
        if (eligible.isEmpty()) {
            return null;
        }
        double total = 0.0;
        for (LootEntry e : eligible) {
            total += e.safeWeight();
        }
        if (total <= 0.0) {
            return eligible.get(rng.nextInt(eligible.size()));
        }
        double r = rng.nextDouble() * total;
        for (LootEntry e : eligible) {
            r -= e.safeWeight();
            if (r <= 0.0) {
                return e;
            }
        }
        return eligible.get(eligible.size() - 1);
    }
}
