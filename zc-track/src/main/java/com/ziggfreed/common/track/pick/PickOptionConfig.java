package com.ziggfreed.common.track.pick;

import java.util.Map;

import javax.annotation.Nonnull;

import com.ziggfreed.common.asset.AbstractKeyedAssetConfig;

/**
 * The {@code defaults < pack < owner} fold of every {@link PickOptionAsset}. An option carries no gate of its own
 * (the pick naming it does). Every merge drops the folded picks ({@link TrackPicks#invalidate}), whose options are
 * filled from these files.
 */
public final class PickOptionConfig extends AbstractKeyedAssetConfig<PickOptionAsset> {

    private static final PickOptionConfig INSTANCE = new PickOptionConfig();

    private PickOptionConfig() {
    }

    @Nonnull
    public static PickOptionConfig getInstance() {
        return INSTANCE;
    }

    @Override
    public synchronized void mergePackLayer(@Nonnull Map<String, PickOptionAsset> layer) {
        super.mergePackLayer(layer);
        TrackPicks.invalidate();
    }

    @Override
    public synchronized void mergeOwnerLayer(@Nonnull Map<String, PickOptionAsset> layer) {
        super.mergeOwnerLayer(layer);
        TrackPicks.invalidate();
    }
}
