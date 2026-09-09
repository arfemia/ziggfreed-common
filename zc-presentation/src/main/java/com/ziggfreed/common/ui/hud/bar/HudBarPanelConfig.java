package com.ziggfreed.common.ui.hud.bar;

import javax.annotation.Nonnull;

import com.ziggfreed.common.asset.AbstractKeyedAssetConfig;

/**
 * The {@code defaults < pack < owner} fold of {@link HudBarPanelAsset}, keyed by panel id.
 *
 * <p>The library ships {@code Default.json} and {@code Grid.json} in its own jar's asset pack, so
 * they ride the PACK layer and a consumer's same-id file replaces one by pack order; the owner
 * layer, from {@code mods/ziggfreedcommon/hud-bar-panels.json}, wins over both. Both readers always
 * answer: before anything has loaded, or if a shipped file were removed, the caller gets the
 * all-defaults panel rather than nothing.
 */
public final class HudBarPanelConfig extends AbstractKeyedAssetConfig<HudBarPanelAsset> {

    private static final HudBarPanelConfig INSTANCE = new HudBarPanelConfig();

    private HudBarPanelConfig() {
    }

    @Nonnull
    public static HudBarPanelConfig getInstance() {
        return INSTANCE;
    }

    /** The stacked left-column panel: the folded {@value HudBarPanelAsset#DEFAULT_ID}, else all defaults. */
    @Nonnull
    public HudBarPanelAsset current() {
        return panel(HudBarPanelAsset.DEFAULT_ID);
    }

    /** The wide top-right panel: the folded {@value HudBarPanelAsset#GRID_ID}, else all defaults. */
    @Nonnull
    public HudBarPanelAsset grid() {
        return panel(HudBarPanelAsset.GRID_ID);
    }

    /** The folded panel under {@code id}, or the all-defaults one when nothing has loaded under it. */
    @Nonnull
    public HudBarPanelAsset panel(@Nonnull String id) {
        HudBarPanelAsset folded = resolve(id);
        return folded != null ? folded : HudBarPanelAsset.defaults();
    }
}
