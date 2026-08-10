package com.ziggfreed.common.instance.reward;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.asset.AbstractKeyedAssetConfig;

/**
 * The {@code defaults < pack < owner} fold authority for {@link LootTable}s, keyed by lowercase table id.
 * A consumer references a table by id from its preset (e.g. {@code InstancePresetAsset.RewardTableId}) and
 * resolves it here at its reward choke-point, so a pack can retune end-game loot purely as DATA, no code
 * change.
 *
 * <p>The fold mechanics (the three layers, lower-casing, idempotent re-import, resolve order) live in the
 * shared {@link AbstractKeyedAssetConfig} base; this singleton adds only the {@link LootTable} type binding
 * and {@link #getInstance()}. The pack-layer merge maps each asset to its runtime model
 * ({@code (id, a) -> a.toLootTable()}).
 *
 * <p>Common ships ZERO jar defaults (the loot schema is 100% generic, never the consumer's concrete item
 * ids), so {@link AbstractKeyedAssetConfig#loadDefaults} stays unused unless a consumer seeds a Java
 * baseline. A consumer authors its tables under the content path it registers the store at (e.g.
 * {@code Server/ZiggfreedCommon/LootTables}) and reads them back through this singleton, holding any
 * runtime (4th) override tier itself.
 */
public final class LootTableConfig extends AbstractKeyedAssetConfig<LootTable> {

    private static final LootTableConfig INSTANCE = new LootTableConfig();

    @Nonnull
    public static LootTableConfig getInstance() {
        return INSTANCE;
    }

    private LootTableConfig() {
    }

    /**
     * Resolve the ADDITIVE union of every loaded table that contributes to the logical {@code tableId}
     * (its own {@link LootTable#tableId()} equals {@code tableId}), so a second pack can add entries to a
     * table WITHOUT overriding the file that owns it. The union concatenates every contributor's guaranteed
     * and pool entries (contributors ordered by {@link LootTable#sourceId()} for a stable, load-order-
     * independent roll); the table-level scalars ({@code rolls}/{@code scorePerBonusRoll}/{@code maxRolls})
     * come from the BASE - the contributor whose own id equals {@code tableId} (else the first by id).
     *
     * <p>Returns {@code null} when no loaded table contributes to {@code tableId}. A lone table (the common
     * case) folds to itself unchanged, so this is a safe drop-in for a plain {@link #resolve} at a roll
     * choke-point.
     *
     * <p>{@code nativeDropList} is a scalar too (the base owns it, same rule as {@code rolls}/
     * {@code scorePerBonusRoll}/{@code maxRolls}): a contributor cannot silently redirect another pack's
     * native item source.
     */
    @Nullable
    public LootTable resolveUnion(@Nonnull String tableId) {
        String tid = tableId.toLowerCase(Locale.ROOT);
        List<LootTable> contributors = new ArrayList<>();
        for (LootTable t : all().values()) {
            if (t.tableId().toLowerCase(Locale.ROOT).equals(tid)) {
                contributors.add(t);
            }
        }
        if (contributors.isEmpty()) {
            return null;
        }
        contributors.sort(Comparator.comparing(t -> t.sourceId().toLowerCase(Locale.ROOT)));
        LootTable base = null;
        for (LootTable t : contributors) {
            if (t.sourceId().toLowerCase(Locale.ROOT).equals(tid)) {
                base = t;
                break;
            }
        }
        if (base == null) {
            base = contributors.get(0);
        }
        if (contributors.size() == 1) {
            return base;
        }
        List<LootEntry> guaranteed = new ArrayList<>();
        List<LootEntry> pool = new ArrayList<>();
        for (LootTable t : contributors) {
            guaranteed.addAll(t.guaranteed());
            pool.addAll(t.pool());
        }
        return new LootTable(guaranteed, pool, base.rolls(), base.scorePerBonusRoll(), base.maxRolls(), tid, tid,
                base.nativeDropList());
    }
}
