package com.ziggfreed.common.encounter.asset;

import javax.annotation.Nonnull;

import com.ziggfreed.common.asset.AbstractKeyedAssetConfig;

/** The folded participation rules ({@code defaults < pack < owner}), keyed by rule id. */
public final class EncounterParticipationConfig extends AbstractKeyedAssetConfig<EncounterParticipationAsset> {

    private static final EncounterParticipationConfig INSTANCE = new EncounterParticipationConfig();

    @Nonnull
    public static EncounterParticipationConfig getInstance() {
        return INSTANCE;
    }

    private EncounterParticipationConfig() {
    }
}
