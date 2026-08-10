package com.ziggfreed.common.instance.encounter;

import javax.annotation.Nonnull;

import com.ziggfreed.common.asset.AbstractKeyedAssetConfig;

/**
 * The {@code defaults < pack < owner} fold authority for {@link MultiPhaseBossAsset}s, keyed
 * by lowercase boss id - the generic, mod-agnostic lift of Kweebec's {@code BossConfig}. A
 * consumer's controller resolves the encounter's boss id here, so a pack can retune a
 * multi-phase boss purely as DATA, no code change.
 *
 * <p>The fold mechanics (the three layers, lower-casing, idempotent re-import, resolve order)
 * live in the shared {@link AbstractKeyedAssetConfig} base; this singleton adds only the
 * {@link MultiPhaseBossAsset} type binding and {@link #getInstance()}. The config holds the
 * decoded ASSET itself ({@code T = MultiPhaseBossAsset}) - the source schema had no separate
 * runtime model, so there is no {@code toModel} mapper; the pack-layer merge maps each asset
 * straight through ({@code (id, a) -> a}).
 *
 * <p>Common ships ZERO jar defaults (the boss schema is 100% generic, never the consumer's
 * concrete ids), so {@link AbstractKeyedAssetConfig#loadDefaults} stays unused here unless a
 * consumer chooses to seed a Java baseline. A consumer authors its bosses under the content
 * path it registers the store at (e.g. {@code Server/ZiggfreedCommon/Bosses}) and reads them
 * back through this singleton, holding any runtime (4th) override tier itself.
 */
public final class MultiPhaseBossConfig extends AbstractKeyedAssetConfig<MultiPhaseBossAsset> {

    private static final MultiPhaseBossConfig INSTANCE = new MultiPhaseBossConfig();

    @Nonnull
    public static MultiPhaseBossConfig getInstance() {
        return INSTANCE;
    }

    private MultiPhaseBossConfig() {
    }
}
