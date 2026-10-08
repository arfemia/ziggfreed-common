package com.ziggfreed.common.reputation.asset;

import javax.annotation.Nonnull;

import com.ziggfreed.common.asset.AbstractKeyedAssetConfig;

/**
 * The {@code defaults < pack < owner} fold of every {@link ReputationAsset}, plus the owner's one switch
 * over the whole module ({@code "$Enabled"} in {@code mods/ziggfreedcommon/reputation.json}).
 *
 * <p>No companion file can be gated, but a Beyond reward row can: a row whose own {@code Requires} names a
 * missing mod pays nothing, and the store's fold ({@link ReputationOwnerLayers#reload}) counts those rows in
 * one row line per missing mod, under {@link #MOD_GATE_STORE}.
 */
public final class ReputationConfig extends AbstractKeyedAssetConfig<ReputationAsset> {

    /** The store's mod-gate label, which its row drop line carries (a contract the season boot pair parses). */
    public static final String MOD_GATE_STORE = "Reputations";

    private static final ReputationConfig INSTANCE = new ReputationConfig();

    private volatile boolean globalEnabled = true;

    private ReputationConfig() {
        super(MOD_GATE_STORE);
    }

    @Nonnull
    public static ReputationConfig getInstance() {
        return INSTANCE;
    }

    /** The owner's switch over every reputation at once; true unless the owner file says otherwise. */
    public boolean isGlobalEnabled() {
        return globalEnabled;
    }

    public void setGlobalEnabled(boolean on) {
        globalEnabled = on;
    }
}
