package com.ziggfreed.common.entity.overhead;

import javax.annotation.Nonnull;

import com.ziggfreed.common.asset.AbstractKeyedAssetConfig;

/**
 * The runtime table of authored {@link OverheadIndicatorAsset}s, folded {@code defaults < pack}
 * like every other keyed asset type and looked up by state id without regard to case.
 *
 * <p>Read once per marker spawn, never per tick: a state's look is resolved when its marker entity
 * is first needed over a host, and the entity carries the answer from then on.
 */
public final class OverheadIndicatorConfig extends AbstractKeyedAssetConfig<OverheadIndicatorAsset> {

    private static final OverheadIndicatorConfig INSTANCE = new OverheadIndicatorConfig();

    @Nonnull
    public static OverheadIndicatorConfig getInstance() {
        return INSTANCE;
    }

    private OverheadIndicatorConfig() {
    }
}
