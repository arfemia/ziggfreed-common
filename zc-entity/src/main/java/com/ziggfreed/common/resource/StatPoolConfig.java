package com.ziggfreed.common.resource;

import javax.annotation.Nonnull;

import com.ziggfreed.common.asset.AbstractKeyedAssetConfig;

/**
 * The pools an owner may tune, folded {@code shipped < owner} by stat id without regard to case: the layer under the
 * owner is each {@code ZigOwnerPool}-tagged stat's own values as its file ships them ({@link StatPools} fills it at
 * every stat load), the owner layer {@code mods/ziggfreedcommon/stat-pools.json}. What is in force is written onto the
 * native stat by {@link StatPools}; nothing reads a pool's numbers from here at a cast.
 */
public final class StatPoolConfig extends AbstractKeyedAssetConfig<StatPoolAsset> {

    private static final StatPoolConfig INSTANCE = new StatPoolConfig();

    @Nonnull
    public static StatPoolConfig getInstance() {
        return INSTANCE;
    }

    private StatPoolConfig() {
    }
}
