package com.ziggfreed.common.instance.claims;

import javax.annotation.Nonnull;

import com.ziggfreed.common.asset.AbstractKeyedAssetConfig;

/**
 * The reward chests in force ({@link RewardChestAsset}), folded {@code defaults < pack < owner} like every keyed asset
 * type: the pack layer from the store's own load event, then the owner's {@code mods/ziggfreedcommon/reward-chests.json}
 * read on the same event ({@link RewardChestOwnerLayers}), since an owner entry has nothing to inherit from until the
 * packs have landed. Ids are lower-cased on every layer.
 *
 * <p>Read it lazily, after {@code LoadedAssetsEvent}: nothing here is meaningful at setup.
 */
public final class RewardChestConfig extends AbstractKeyedAssetConfig<RewardChestAsset> {

    private static final RewardChestConfig INSTANCE = new RewardChestConfig();

    @Nonnull
    public static RewardChestConfig getInstance() {
        return INSTANCE;
    }

    private RewardChestConfig() {
    }
}
