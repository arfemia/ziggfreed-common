package com.ziggfreed.common.track.asset;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import javax.annotation.Nonnull;

import com.ziggfreed.common.asset.AbstractKeyedAssetConfig;
import com.ziggfreed.common.track.curve.TrackCurves;

/**
 * The {@code defaults < pack < owner} fold of every {@link TrackCurveAsset}. Every merge drops the tabulated
 * curves ({@link TrackCurves#invalidate}), so the next read builds each table from what is folded now and the
 * curve generation moves for whoever kept a value derived from a level.
 *
 * <p>The owner layer has two writers, kept apart: the owner file {@code track-curves.json}
 * ({@link TrackCurveOwnerLayers}, through {@link #mergeOwnerLayer}) and a consumer turning the server owner's own
 * older settings into curves ({@link #ownerWord}, per owner). The layer is their union, the file's entry winning
 * over a consumer's of the same id, so neither read replaces the other.
 */
public final class TrackCurveConfig extends AbstractKeyedAssetConfig<TrackCurveAsset> {

    private static final TrackCurveConfig INSTANCE = new TrackCurveConfig();

    /** The curves modules derive in code, per owner: the defaults layer. */
    private final ProvidedLayer<TrackCurveAsset> provided = new ProvidedLayer<>();

    /** The curves consumers write from the server owner's own older settings, per owner: under the owner file. */
    private final ProvidedLayer<TrackCurveAsset> ownerWords = new ProvidedLayer<>();

    /** What the owner file said at its last read, by lower-cased id. */
    @Nonnull
    private Map<String, TrackCurveAsset> fromFile = Map.of();

    /** What every consumer's owner word says now, by lower-cased id. */
    @Nonnull
    private Map<String, TrackCurveAsset> fromConsumers = Map.of();

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

    /**
     * The curves {@code owner} writes from the server owner's own settings kept in that consumer's older files (a
     * level formula's settings), by id, replacing whatever it wrote before; an empty map withdraws them. They sit in
     * the owner layer over every pack file, under {@code track-curves.json}'s entry of the same id. Drops every
     * tabulated curve. Any thread.
     */
    public synchronized void ownerWord(@Nonnull String owner, @Nonnull Map<String, TrackCurveAsset> curves) {
        fromConsumers = ownerWords.provide(owner, curves);
        super.mergeOwnerLayer(union());
        TrackCurves.invalidate();
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

    /** The owner file's layer ({@link TrackCurveOwnerLayers}), over every consumer's owner word. */
    @Override
    public synchronized void mergeOwnerLayer(@Nonnull Map<String, TrackCurveAsset> layer) {
        Map<String, TrackCurveAsset> file = new LinkedHashMap<>();
        layer.forEach((id, curve) -> {
            if (id != null && curve != null) {
                file.put(id.trim().toLowerCase(Locale.ROOT), curve);
            }
        });
        fromFile = Map.copyOf(file);
        super.mergeOwnerLayer(union());
        TrackCurves.invalidate();
    }

    /** The owner layer: every consumer's owner word, the file's entries over them id by id. */
    @Nonnull
    private Map<String, TrackCurveAsset> union() {
        Map<String, TrackCurveAsset> out = new LinkedHashMap<>(fromConsumers);
        out.putAll(fromFile);
        return out;
    }
}
