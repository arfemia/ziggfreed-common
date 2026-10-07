package com.ziggfreed.common.almanac.asset;

import java.util.Map;

import javax.annotation.Nonnull;

import com.ziggfreed.common.almanac.AlmanacIndex;
import com.ziggfreed.common.asset.AbstractKeyedAssetConfig;
import com.ziggfreed.common.util.SafeLog;
import com.ziggfreed.common.validation.Finding;

/**
 * Every season page, folded {@code defaults < pack < owner} with ids lower-cased, and the stat index
 * the counter reads, rebuilt on every layer change so a hot reload is in force on the next moment. Each
 * layer's pages say what is wrong with them as they arrive ({@link AlmanacEntryAsset#findings}), once
 * per load, in the log.
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
        report(layer);
    }

    @Override
    public synchronized void mergeOwnerLayer(@Nonnull Map<String, AlmanacEntryAsset> layer) {
        super.mergeOwnerLayer(layer);
        index = AlmanacIndex.of(all());
        report(layer);
    }

    /** The stat lines in force right now, filed by kind. */
    @Nonnull
    public AlmanacIndex index() {
        return index;
    }

    private static void report(@Nonnull Map<String, AlmanacEntryAsset> layer) {
        for (AlmanacEntryAsset page : layer.values()) {
            if (page == null) {
                continue;
            }
            for (Finding finding : page.findings()) {
                SafeLog.warn("[almanac] season '" + finding.sourceId() + "': " + finding.message());
            }
        }
    }
}
