package com.ziggfreed.common.instance.preset;

import javax.annotation.Nonnull;

import com.ziggfreed.common.asset.AbstractKeyedAssetConfig;

/**
 * The {@code defaults < pack < owner} fold authority for {@link InstancePreset}s, keyed
 * by lowercase preset id - the cross-cutting twin of a consumer's gameplay preset config
 * (e.g. Kweebec's {@code PresetConfig} for {@code RuleSet}s). The two key on the SAME id,
 * so a consumer resolves both for one preset without a parallel schema.
 *
 * <p>The fold mechanics (the three layers, lower-casing, idempotent re-import, resolve
 * order) live in the shared {@link AbstractKeyedAssetConfig} base; this singleton adds
 * only the {@link InstancePreset} type binding and {@link #getInstance()}. A consumer
 * holds the runtime (4th) override tier itself (e.g. Kweebec's {@code KweebecNightmareAPI}),
 * composing it above {@link #resolve}.
 *
 * <p>The cross-cutting preset store is registered by ziggfreed-common itself at
 * {@code Server/ZiggfreedCommon/Instances} (see {@code asset/FrameworkAssetRegistrar});
 * a consumer authors its presets there and reads them back through this singleton.
 */
public final class InstancePresetConfig extends AbstractKeyedAssetConfig<InstancePreset> {

    private static final InstancePresetConfig INSTANCE = new InstancePresetConfig();

    @Nonnull
    public static InstancePresetConfig getInstance() {
        return INSTANCE;
    }

    private InstancePresetConfig() {
    }
}
