package com.ziggfreed.common.reputation.asset;

import javax.annotation.Nonnull;

import com.ziggfreed.common.asset.AbstractKeyedAssetConfig;

/**
 * The {@code defaults < pack < owner} fold of every {@link ReputationAsset}, plus the owner's one switch
 * over the whole module ({@code "$Enabled"} in {@code mods/ziggfreedcommon/reputation.json}).
 */
public final class ReputationConfig extends AbstractKeyedAssetConfig<ReputationAsset> {

    private static final ReputationConfig INSTANCE = new ReputationConfig();

    private volatile boolean globalEnabled = true;

    private ReputationConfig() {
    }

    @Nonnull
    public static ReputationConfig getInstance() {
        return INSTANCE;
    }

    /** The owner's switch over every reputation at once; true unless the owner file says otherwise. */
    public boolean isGlobalEnabled() {
        return globalEnabled;
    }

    public void setGlobalEnabled(boolean on) {
        globalEnabled = on;
    }
}
