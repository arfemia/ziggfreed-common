package com.ziggfreed.common.worldmap;

import javax.annotation.Nonnull;

import com.ziggfreed.common.asset.AbstractKeyedAssetConfig;

/**
 * The runtime table of {@link GatewayAsset}s, looked up without regard to case: the base game's
 * portals as {@link PortalGateways} read them (the defaults layer), under every pack's files, under
 * the owner's. A file replaces a read gateway of the same id whole.
 */
public final class GatewayConfig extends AbstractKeyedAssetConfig<GatewayAsset> {

    private static final GatewayConfig INSTANCE = new GatewayConfig();

    @Nonnull
    public static GatewayConfig getInstance() {
        return INSTANCE;
    }

    private GatewayConfig() {
    }
}
