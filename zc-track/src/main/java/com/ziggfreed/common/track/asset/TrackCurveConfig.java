package com.ziggfreed.common.track.asset;

import java.util.Map;

import javax.annotation.Nonnull;

import com.ziggfreed.common.asset.AbstractKeyedAssetConfig;
import com.ziggfreed.common.track.curve.TrackCurves;

/**
 * The {@code defaults < pack < owner} fold of every {@link TrackCurveAsset}. Every merge drops the tabulated
 * curves ({@link TrackCurves#invalidate}), so the next read builds each table from what is folded now and the
 * curve generation moves for whoever kept a value derived from a level.
 */
public final class TrackCurveConfig extends AbstractKeyedAssetConfig<TrackCurveAsset> {

    private static final TrackCurveConfig INSTANCE = new TrackCurveConfig();

    /** The curves modules derive in code, per owner: the defaults layer. */
    private final ProvidedLayer<TrackCurveAsset> provided = new ProvidedLayer<>();

    private TrackCurveConfig() {
    }

    @Nonnull
    public static TrackCurveConfig getInstance() {
        return INSTANCE;
    }

    /**
     * The curves {@code owner} derives in code from assets of its own (a reputation's ladder as a curve), by id,
     * replacing whatever it provided before; an empty map withdraws them. They sit under every pack file, so a
     * pack's curve of the same id replaces one whole. Drops every tabulated curve. Any thread.
     */
    public synchronized void provide(@Nonnull String owner, @Nonnull Map<String, TrackCurveAsset> curves) {
        loadDefaults(provided.provide(owner, curves));
    }

    @Override
    public synchronized void loadDefaults(@Nonnull Map<String, TrackCurveAsset> jarDefaults) {
        super.loadDefaults(jarDefaults);
        TrackCurves.invalidate();
    }

    @Override
    public synchronized void mergePackLayer(@Nonnull Map<String, TrackCurveAsset> layer) {
        super.mergePackLayer(layer);
        TrackCurves.invalidate();
    }

    @Override
    public synchronized void mergeOwnerLayer(@Nonnull Map<String, TrackCurveAsset> layer) {
        super.mergeOwnerLayer(layer);
        TrackCurves.invalidate();
    }
}
