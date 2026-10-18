package com.ziggfreed.common.track.source;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.annotation.Nonnull;

import com.ziggfreed.common.asset.AbstractKeyedAssetConfig;
import com.ziggfreed.common.progress.gate.StoreGates;

/**
 * The {@code defaults < pack < owner} fold of every {@link TrackSourceAsset}. A file may gate on a mod through its
 * top-level {@code Requires} (the mod gate): the store's fold and its owner reader count what they leave out under
 * {@link #MOD_GATE_STORE}. Every merge drops the source index ({@link TrackSources#invalidate}), so the next moment
 * reads the tables folded from what is here now, and names once the files whose gate reads more than mod presence.
 */
public final class TrackSourceConfig extends AbstractKeyedAssetConfig<TrackSourceAsset> {

    /** The store's mod-gate label, which its drop lines carry (a contract the season boot pair parses). */
    public static final String MOD_GATE_STORE = "TrackSources";

    private static final TrackSourceConfig INSTANCE = new TrackSourceConfig();

    private TrackSourceConfig() {
        super(MOD_GATE_STORE);
    }

    @Nonnull
    public static TrackSourceConfig getInstance() {
        return INSTANCE;
    }

    @Override
    public synchronized void loadDefaults(@Nonnull Map<String, TrackSourceAsset> jarDefaults) {
        super.loadDefaults(jarDefaults);
        TrackSources.invalidate();
    }

    @Override
    public synchronized void mergePackLayer(@Nonnull Map<String, TrackSourceAsset> layer) {
        super.mergePackLayer(layer);
        folded();
    }

    @Override
    public synchronized void mergeOwnerLayer(@Nonnull Map<String, TrackSourceAsset> layer) {
        super.mergeOwnerLayer(layer);
        folded();
    }

    private void folded() {
        TrackSources.invalidate();
        List<String> widened = new ArrayList<>();
        for (Map.Entry<String, TrackSourceAsset> entry : all().entrySet()) {
            if (entry.getValue() != null && entry.getValue().liveRequires() != null) {
                widened.add(entry.getKey());
            }
        }
        StoreGates.reportWidened(MOD_GATE_STORE, widened);
    }
}
