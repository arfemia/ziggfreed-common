package com.ziggfreed.common.loot.stamp;

import javax.annotation.Nonnull;

import com.ziggfreed.common.asset.AbstractKeyedAssetConfig;

/**
 * The runtime table of named {@link RollPoolAsset}s, folded {@code defaults < pack < owner} like
 * every other keyed asset type: a pack shipping a file with an existing id replaces that pool
 * outright, and a server owner's layer wins over both.
 */
public final class RollPoolConfig extends AbstractKeyedAssetConfig<RollPoolAsset> {

    private static final RollPoolConfig INSTANCE = new RollPoolConfig();

    @Nonnull
    public static RollPoolConfig getInstance() {
        return INSTANCE;
    }

    private RollPoolConfig() {
    }
}
