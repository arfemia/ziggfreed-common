package com.ziggfreed.common.track.price;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.asset.AbstractKeyedAssetConfig;

/**
 * The {@code defaults < pack < owner} fold of every {@link TrackPriceAsset}, keyed by price id: the one price table
 * every minutes-priced reward reads ({@link TrackPricing}). The library ships its rows in its own jar's asset pack,
 * a pack adds or retunes rows by id, and the owner layer is {@code mods/ziggfreedcommon/track-prices.json}
 * ({@link TrackPriceOwnerLayers}).
 */
public final class TrackPriceConfig extends AbstractKeyedAssetConfig<TrackPriceAsset> {

    private static final TrackPriceConfig INSTANCE = new TrackPriceConfig();

    private TrackPriceConfig() {
    }

    @Nonnull
    public static TrackPriceConfig getInstance() {
        return INSTANCE;
    }

    /** The row {@code priceId} names (any case, trimmed), or null for a blank id or one no layer holds. */
    @Nullable
    public TrackPriceAsset price(@Nullable String priceId) {
        return priceId == null || priceId.isBlank() ? null : resolve(priceId.trim());
    }
}
