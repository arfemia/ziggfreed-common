package com.ziggfreed.common.loot;

import javax.annotation.Nonnull;

import com.ziggfreed.common.asset.AbstractKeyedAssetConfig;

/**
 * The runtime table of named {@link LootableAsset}s, folded {@code defaults < pack < owner} like
 * every other keyed asset type: a pack that ships a file with an existing id replaces that table
 * outright, and a server owner's layer wins over both.
 */
public final class LootableConfig extends AbstractKeyedAssetConfig<LootableAsset> {

    private static final LootableConfig INSTANCE = new LootableConfig();

    @Nonnull
    public static LootableConfig getInstance() {
        return INSTANCE;
    }

    private LootableConfig() {
    }
}
