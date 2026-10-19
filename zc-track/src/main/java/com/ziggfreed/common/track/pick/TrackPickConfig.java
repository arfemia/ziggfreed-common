package com.ziggfreed.common.track.pick;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.annotation.Nonnull;

import com.ziggfreed.common.asset.AbstractKeyedAssetConfig;
import com.ziggfreed.common.progress.gate.StoreGates;

/**
 * The {@code defaults < pack < owner} fold of every {@link TrackPickAsset}. A file may gate on a mod through its
 * top-level {@code Requires} (the mod gate): the store's fold and its owner reader count what they leave out under
 * {@link #MOD_GATE_STORE}. Every merge drops the folded picks ({@link TrackPicks#invalidate}), so the next read folds
 * from what is here now, and names once the files whose gate reads more than mod presence.
 */
public final class TrackPickConfig extends AbstractKeyedAssetConfig<TrackPickAsset> {

    /** The store's mod-gate label, which its drop lines carry (a contract the season boot pair parses). */
    public static final String MOD_GATE_STORE = "TrackPicks";

    private static final TrackPickConfig INSTANCE = new TrackPickConfig();

    private TrackPickConfig() {
        super(MOD_GATE_STORE);
    }

    @Nonnull
    public static TrackPickConfig getInstance() {
        return INSTANCE;
    }

    @Override
    public synchronized void mergePackLayer(@Nonnull Map<String, TrackPickAsset> layer) {
        super.mergePackLayer(layer);
        folded();
    }

    @Override
    public synchronized void mergeOwnerLayer(@Nonnull Map<String, TrackPickAsset> layer) {
        super.mergeOwnerLayer(layer);
        folded();
    }

    private void folded() {
        TrackPicks.invalidate();
        List<String> widened = new ArrayList<>();
        for (Map.Entry<String, TrackPickAsset> entry : all().entrySet()) {
            if (entry.getValue() != null && entry.getValue().liveRequires() != null) {
                widened.add(entry.getKey());
            }
        }
        StoreGates.reportWidened(MOD_GATE_STORE, widened);
    }
}
