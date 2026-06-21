package com.ziggfreed.common.instance.reward;

import javax.annotation.Nonnull;

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
}
