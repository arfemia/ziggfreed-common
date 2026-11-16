package com.ziggfreed.common.instance.dungeon.omen;

import javax.annotation.Nonnull;

import com.ziggfreed.common.asset.AbstractKeyedAssetConfig;

/**
 * The dungeon omens in force ({@link DungeonOmenAsset}), folded {@code defaults < pack < owner} like every keyed asset
 * type, ids lower-cased. Read it lazily, after {@code LoadedAssetsEvent}.
 */
public final class DungeonOmenConfig extends AbstractKeyedAssetConfig<DungeonOmenAsset> {

    private static final DungeonOmenConfig INSTANCE = new DungeonOmenConfig();

    @Nonnull
    public static DungeonOmenConfig getInstance() {
        return INSTANCE;
    }

    private DungeonOmenConfig() {
    }
}
