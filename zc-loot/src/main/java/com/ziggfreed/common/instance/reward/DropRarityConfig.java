package com.ziggfreed.common.instance.reward;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import javax.annotation.Nonnull;

import com.ziggfreed.common.asset.AbstractKeyedAssetConfig;

/**
 * The drop-rarity bands in force ({@link DropRarityAsset}), folded {@code defaults < pack < owner} like every keyed
 * asset type: the pack layer from the store's own load event, then the owner's
 * {@code mods/ziggfreedcommon/drop-rarities.json} read on the same event ({@link DropRarityOwnerLayers}). Each fold
 * rebuilds the one ladder {@link DropRarity} reads ({@link #bands}): every enabled band that writes an
 * {@code AtMostPercent}, narrowest first.
 *
 * <p>Read it lazily, after {@code LoadedAssetsEvent}: before the bands load, no drop reads as any band.
 */
public final class DropRarityConfig extends AbstractKeyedAssetConfig<DropRarityAsset> {

    /** One band of the ladder: the quality a drop reads as, and the most chance it may have to read so. */
    public record Band(@Nonnull String quality, double atMostPercent) {
    }

    private static final DropRarityConfig INSTANCE = new DropRarityConfig();

    @Nonnull
    private volatile List<Band> bands = List.of();

    @Nonnull
    public static DropRarityConfig getInstance() {
        return INSTANCE;
    }

    private DropRarityConfig() {
    }

    @Override
    public synchronized void mergePackLayer(@Nonnull Map<String, DropRarityAsset> layer) {
        super.mergePackLayer(layer);
        rebuild();
    }

    @Override
    public synchronized void mergeOwnerLayer(@Nonnull Map<String, DropRarityAsset> layer) {
        super.mergeOwnerLayer(layer);
        rebuild();
    }

    /** The ladder: every enabled band with an {@code AtMostPercent}, narrowest first (then by quality id). */
    @Nonnull
    public List<Band> bands() {
        return bands;
    }

    private void rebuild() {
        List<Band> made = new ArrayList<>();
        for (DropRarityAsset band : all().values()) {
            Double atMost = band == null ? null : band.atMostPercent();
            String quality = band == null ? null : band.quality();
            if (atMost != null && quality != null && band.isEnabled()) {
                made.add(new Band(quality, atMost));
            }
        }
        made.sort(Comparator.comparingDouble(Band::atMostPercent).thenComparing(Band::quality));
        bands = List.copyOf(made);
    }
}
