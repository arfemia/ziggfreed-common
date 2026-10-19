package com.ziggfreed.common.track.boost;

import java.util.Map;

import javax.annotation.Nonnull;

import com.ziggfreed.common.asset.AbstractKeyedAssetConfig;
import com.ziggfreed.common.track.gain.GainPlans;

/**
 * The {@code defaults < pack < owner} fold of {@link BoostSettingsAsset}, keyed by record id. The library ships
 * {@code Default.json} in its own jar's asset pack, so it rides the pack layer; the owner layer, from
 * {@code mods/ziggfreedcommon/boost-settings.json}, wins over both. A server with no record at all reads every
 * default ({@link #settings}). Every merge moves the compiled gain plans, since the cap is compiled into them.
 */
public final class BoostSettingsConfig extends AbstractKeyedAssetConfig<BoostSettingsAsset> {

    private static final BoostSettingsConfig INSTANCE = new BoostSettingsConfig();

    /** What a server with no record reads: every leaf unauthored. */
    private static final BoostSettingsAsset DEFAULTS = new BoostSettingsAsset();

    private BoostSettingsConfig() {
    }

    @Nonnull
    public static BoostSettingsConfig getInstance() {
        return INSTANCE;
    }

    @Override
    public synchronized void mergePackLayer(@Nonnull Map<String, BoostSettingsAsset> layer) {
        super.mergePackLayer(layer);
        GainPlans.invalidateAll();
    }

    @Override
    public synchronized void mergeOwnerLayer(@Nonnull Map<String, BoostSettingsAsset> layer) {
        super.mergeOwnerLayer(layer);
        GainPlans.invalidateAll();
    }

    /** The folded record every boost reads, or the defaults when no layer authored one. */
    @Nonnull
    public BoostSettingsAsset settings() {
        return resolveOrDefault(BoostSettingsAsset.SHARED_ID, DEFAULTS);
    }
}
