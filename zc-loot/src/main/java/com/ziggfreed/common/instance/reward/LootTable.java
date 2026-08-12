package com.ziggfreed.common.instance.reward;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.loot.FactorGate;
import com.ziggfreed.common.loot.FactorLookup;
import com.ziggfreed.common.loot.LootFactors;
import com.ziggfreed.common.util.WeightedPick;

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
 * to a uniform pick. Immutable; safe to share and call from any thread.
 *
 * <p><b>Eligibility rides the shared vocabulary.</b> A pool entry's score requirement and its
 * win/loss gate are {@code FactorCondition}s over {@link LootFactors}, walked by the same
 * {@link FactorGate} every other piece of gated content uses, and the pick itself runs through the
 * one {@link WeightedPick} primitive. So "unlocks at 4000 points" is an ordinary reading here rather
 * than a rule only this class knows, and a future roll can gate on it beside any other factor.
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
        FactorLookup outcome = LootFactors.lookupFor(score, win);
        List<InstanceReward> out = new ArrayList<>();
        for (LootEntry g : guaranteed) {
            if (FactorGate.pass(g.gateConditions(), outcome)) {
                out.add(g.resolve(rng));
            }
        }
        List<LootEntry> eligible = new ArrayList<>();
        for (LootEntry e : pool) {
            if (FactorGate.pass(e.conditions(), outcome)) {
                eligible.add(e);
            }
        }
        if (eligible.isEmpty()) {
            return out;
        }
        for (LootEntry pick : WeightedPick.some(eligible, LootEntry::safeWeight,
                rollCount(score), false, rng::nextDouble)) {
            out.add(pick.resolve(rng));
        }
        return out;
    }

    /**
     * How many pool picks a run at {@code score} earns: the base {@link #rolls}, plus one per
     * {@link #scorePerBonusRoll} points, held at {@link #maxRolls}.
     */
    public int rollCount(int score) {
        int n = rolls;
        if (scorePerBonusRoll > 0) {
            n += Math.max(0, score) / scorePerBonusRoll;
        }
        return maxRolls > 0 ? Math.min(n, maxRolls) : n;
    }
}
