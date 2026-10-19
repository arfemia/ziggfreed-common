package com.ziggfreed.common.track.pick;

import java.util.Map;

import javax.annotation.Nonnull;

import com.ziggfreed.common.asset.AbstractKeyedAssetConfig;

/**
 * The {@code defaults < pack} fold of every {@link PickDeckAsset}. No owner file: an owner retunes the picks and
 * options a deck draws for, and a pack ships its own deck by id. Every merge drops the folded picks
 * ({@link TrackPicks#invalidate}), whose roads a deck draws.
 */
public final class PickDeckConfig extends AbstractKeyedAssetConfig<PickDeckAsset> {

    private static final PickDeckConfig INSTANCE = new PickDeckConfig();

    private PickDeckConfig() {
    }

    @Nonnull
    public static PickDeckConfig getInstance() {
        return INSTANCE;
    }

    @Override
    public synchronized void mergePackLayer(@Nonnull Map<String, PickDeckAsset> layer) {
        super.mergePackLayer(layer);
        TrackPicks.invalidate();
    }
}
