package com.ziggfreed.common.loot.stamp;

import javax.annotation.Nonnull;

import com.ziggfreed.common.asset.AbstractKeyedAssetConfig;

/**
 * The runtime table of authored {@link StatDisplayAsset}s, folded {@code defaults < pack < owner}
 * like every other keyed asset type.
 *
 * <p>It is read on the naming path, which runs while a tooltip is being composed, so a lookup miss
 * has to be as cheap as a hit - and it is: an unauthored stat resolves to null and falls straight
 * through to the client's own label.
 */
public final class StatDisplayConfig extends AbstractKeyedAssetConfig<StatDisplayAsset> {

    private static final StatDisplayConfig INSTANCE = new StatDisplayConfig();

    @Nonnull
    public static StatDisplayConfig getInstance() {
        return INSTANCE;
    }

    private StatDisplayConfig() {
    }
}
