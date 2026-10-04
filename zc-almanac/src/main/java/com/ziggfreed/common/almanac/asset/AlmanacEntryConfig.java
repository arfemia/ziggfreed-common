package com.ziggfreed.common.almanac.asset;

import java.util.Map;

import javax.annotation.Nonnull;

import com.ziggfreed.common.almanac.AlmanacIndex;
import com.ziggfreed.common.asset.AbstractKeyedAssetConfig;

/**
 * Every season page, folded {@code defaults < pack < owner} with ids lower-cased, and the stat index
 * the counter reads, rebuilt on every layer change so a hot reload is in force on the next moment.
 */
public final class AlmanacEntryConfig extends AbstractKeyedAssetConfig<AlmanacEntryAsset> {

    private static final AlmanacEntryConfig INSTANCE = new AlmanacEntryConfig();

    private volatile AlmanacIndex index = AlmanacIndex.EMPTY;

    private AlmanacEntryConfig() {
    }

    @Nonnull
    public static AlmanacEntryConfig getInstance() {
        return INSTANCE;
    }

    @Override
    public synchronized void mergePackLayer(@Nonnull Map<String, AlmanacEntryAsset> layer) {
        super.mergePackLayer(layer);
        index = AlmanacIndex.of(all());
    }

    @Override
    public synchronized void mergeOwnerLayer(@Nonnull Map<String, AlmanacEntryAsset> layer) {
        super.mergeOwnerLayer(layer);
        index = AlmanacIndex.of(all());
    }

    /** The stat lines in force right now, filed by kind. */
    @Nonnull
    public AlmanacIndex index() {
        return index;
    }
}
