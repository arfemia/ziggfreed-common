package com.ziggfreed.common.instance.reward;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * A generic, mod-agnostic score-tiered reward LOOT TABLE: a {@link #guaranteed} list (each entry emitted
 * once when its {@link WinGate} admits the outcome) plus a weighted, score-gated {@link #pool} of
 * {@link LootEntry} templates rolled by a player's run score. The runtime form of a {@code LootTableAsset}
 * (the codec face); the consumer resolves the table by id from {@code LootTableConfig} and {@link #roll}s
 * it at its reward choke-point with the player's score and win/loss outcome.
 *
 * <p><b>Additive by contribution.</b> The runtime table a consumer rolls may be the UNION of several
 * authored assets that share one {@link #tableId} (see {@code LootTableConfig.resolveUnion}), so one pack
 * contributes entries to another's table WITHOUT overriding its file. {@link #sourceId} is the id of the
 * asset a single table was decoded from; {@link #tableId} is the logical id contributions group under
 * (defaulting to {@code sourceId}). A union carries the base's scalars and both ids set to the logical id.
 *
 * <p><b>"Better loot for a better score"</b> works two ways: a higher {@code score} (a) makes more
 * {@code s}-gated premium entries ELIGIBLE and (b) earns BONUS ROLLS via {@link #scorePerBonusRoll}
 * (+1 roll per that many points), up to {@link #maxRolls}.
 *
 * <p><b>Determinism</b> is the contract (mirrors {@code instance/encounter/SpawnRoster}): {@link #roll}
 * takes the caller's {@link Random}, so the same seed yields the same loot - testable, and reproducible off
 * a round/player seed. The weighted-pick is with-replacement; a zero-total-weight eligible set falls back
 * to a uniform pick (the {@code SpawnRoster} convention). Immutable; safe to share and call from any thread.
 *
 * <p><b>Native item delegation</b> (XP-agnostic, engine-touching): {@link #nativeDropList} is an OPTIONAL
 * id of a native Hytale {@code ItemDropList} asset this table's item rewards delegate to. {@link #roll}
 * itself stays pure and unaware of it (a deliberate separation: this record never touches the live engine);
 * {@code NativeLootService.rollTable(table, score, win, rng)} is the engine-touching wrapper that calls
 * {@link #roll} for the non-native (command/currency/gated) entries UNCHANGED, then merges in the native
 * roll's items on top. {@code null}/blank means no native delegation (the pre-native behavior, byte-for-
 * byte unchanged).
 */
public record LootTable(@Nonnull List<LootEntry> guaranteed, @Nonnull List<LootEntry> pool,
                        int rolls, int scorePerBonusRoll, int maxRolls,
                        @Nonnull String sourceId, @Nonnull String tableId,
                        @Nullable String nativeDropList) {

    public LootTable {
        guaranteed = List.copyOf(guaranteed);
        pool = List.copyOf(pool);
    }

    /**
     * Roll this table for a player with {@code score} and {@code win} outcome: each guaranteed entry whose
     * {@link WinGate} admits the outcome, then up to {@code clamp(rolls + score/scorePerBonusRoll, 0,
     * maxRolls)} weighted picks among the pool entries eligible at {@code score} ({@code minScore <= score})
     * whose gate admits the outcome, each resolved to a concrete quantity.
     *
     * @param score the player's run score (the eligibility + bonus-roll driver); negative reads as 0
     * @param win   the run outcome; gates which entries are eligible (an un-annotated entry is win-only)
     * @param rng   the caller's RNG (one stream drives both the pick and each quantity roll)
     * @return the eligible guaranteed ++ rolled rewards (never null; possibly empty on a loss)
     */
    @Nonnull
    public List<InstanceReward> roll(int score, boolean win, @Nonnull Random rng) {
        List<InstanceReward> out = new ArrayList<>();
        for (LootEntry g : guaranteed) {
            if (g.gate().matches(win)) {
                out.add(g.resolve(rng));
            }
        }
        int s = Math.max(0, score);
        List<LootEntry> eligible = new ArrayList<>();
        for (LootEntry e : pool) {
            if (e.minScore() <= s && e.gate().matches(win)) {
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
