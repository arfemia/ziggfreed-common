package com.ziggfreed.common.ui.hud.bar;

import javax.annotation.Nonnull;

import com.ziggfreed.common.asset.AbstractKeyedAssetConfig;

/**
 * The {@code defaults < pack < owner} fold of {@link HudBarPanelAsset}, keyed by panel id.
 *
 * <p>The library ships {@code Default.json} in its own jar's asset pack, so it rides the PACK layer
 * and a consumer's same-id file replaces it by pack order; the owner layer, from
 * {@code mods/ziggfreedcommon/hud-bar-panels.json}, wins over both. {@link #current()} is the panel
 * every bar is drawn on, and it always answers: before anything has loaded, or if the shipped file
 * were removed, it is the all-defaults panel.
 */
public final class HudBarPanelConfig extends AbstractKeyedAssetConfig<HudBarPanelAsset> {

    private static final HudBarPanelConfig INSTANCE = new HudBarPanelConfig();

    private HudBarPanelConfig() {
    }

    @Nonnull
    public static HudBarPanelConfig getInstance() {
        return INSTANCE;
    }

    /** The panel the bars are drawn on: the folded {@value HudBarPanelAsset#DEFAULT_ID}, else all defaults. */
    @Nonnull
    public HudBarPanelAsset current() {
        HudBarPanelAsset folded = resolve(HudBarPanelAsset.DEFAULT_ID);
        return folded != null ? folded : HudBarPanelAsset.defaults();
    }
}
