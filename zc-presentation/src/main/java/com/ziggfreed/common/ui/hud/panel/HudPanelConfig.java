package com.ziggfreed.common.ui.hud.panel;

import javax.annotation.Nonnull;

import com.ziggfreed.common.asset.AbstractKeyedAssetConfig;

/**
 * The {@code defaults < pack < owner} fold of {@link HudPanelAsset}, keyed by panel id.
 *
 * <p>The library ships {@code Activity_Ledger.json} and {@code World_Bars.json} in its own jar's
 * asset pack, so they ride the PACK layer and a consumer's same-id file replaces one by pack order; the owner
 * layer, from {@code mods/ziggfreedcommon/hud-panels.json}, wins over both. Both readers always
 * answer: before anything has loaded, or if a shipped file were removed, the caller gets the
 * all-defaults panel rather than nothing.
 */
public final class HudPanelConfig extends AbstractKeyedAssetConfig<HudPanelAsset> {

    private static final HudPanelConfig INSTANCE = new HudPanelConfig();

    private HudPanelConfig() {
    }

    @Nonnull
    public static HudPanelConfig getInstance() {
        return INSTANCE;
    }

    /** The Activity ledger, the tall panel: the folded {@value HudPanelAsset#LEDGER_ID}, else all defaults. */
    @Nonnull
    public HudPanelAsset ledger() {
        return panel(HudPanelAsset.LEDGER_ID);
    }

    /** The World bars, the wide panel: the folded {@value HudPanelAsset#WORLD_ID}, else all defaults. */
    @Nonnull
    public HudPanelAsset world() {
        return panel(HudPanelAsset.WORLD_ID);
    }

    /** The folded panel under {@code id}, or the all-defaults one when nothing has loaded under it. */
    @Nonnull
    public HudPanelAsset panel(@Nonnull String id) {
        HudPanelAsset folded = resolve(id);
        return folded != null ? folded : HudPanelAsset.defaults();
    }
}
