package com.ziggfreed.common.playerstats.asset;

import java.util.Map;

import javax.annotation.Nonnull;

import com.ziggfreed.common.asset.AbstractKeyedAssetConfig;
import com.ziggfreed.common.playerstats.StatIndex;

/**
 * The runtime table of authored {@link StatAsset}s, folded {@code defaults < pack < owner} like every
 * other keyed type, with the {@link StatIndex} the counter and the board read rebuilt whole on every
 * load. The library's own stats ship as JSON in its jar and ride the pack layer, so a pack's same-id
 * file replaces one.
 */
public final class StatConfig extends AbstractKeyedAssetConfig<StatAsset> {

    private static final StatConfig INSTANCE = new StatConfig();

    private volatile StatIndex index = StatIndex.EMPTY;

    @Nonnull
    public static StatConfig getInstance() {
        return INSTANCE;
    }

    private StatConfig() {
    }

    @Override
    public synchronized void mergePackLayer(@Nonnull Map<String, StatAsset> layer) {
        super.mergePackLayer(layer);
        index = StatIndex.of(all());
    }

    @Override
    public synchronized void mergeOwnerLayer(@Nonnull Map<String, StatAsset> layer) {
        super.mergeOwnerLayer(layer);
        index = StatIndex.of(all());
    }

    /** The stats in force right now, and their {@code From} entries filed by kind. */
    @Nonnull
    public StatIndex index() {
        return index;
    }
}
