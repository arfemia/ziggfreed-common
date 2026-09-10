package com.ziggfreed.common.ui.hud.panel;

import java.util.Comparator;
import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.asset.AbstractKeyedAssetConfig;

/**
 * The {@code defaults < pack < owner} fold of every {@link HudSpotAsset}, keyed by
 * spot id.
 *
 * <p>The library ships its three spots in its own jar's asset pack, so they ride the PACK layer
 * and a consumer's same-id file replaces one by pack order; the owner layer, from
 * {@code mods/ziggfreedcommon/hud-spots.json}, wins over both. A spot nobody authored
 * answers null, and every caller treats that as "sit where the document says".
 */
public final class HudSpotConfig extends AbstractKeyedAssetConfig<HudSpotAsset> {

    private static final HudSpotConfig INSTANCE = new HudSpotConfig();

    /** Pickers list by authored order, then id, so the list reads the same across reloads. */
    private static final Comparator<HudSpotAsset> LISTING =
            Comparator.comparingInt(HudSpotAsset::order)
                    .thenComparing(HudSpotAsset::getId, String.CASE_INSENSITIVE_ORDER);

    private HudSpotConfig() {
    }

    @Nonnull
    public static HudSpotConfig getInstance() {
        return INSTANCE;
    }

    /** The folded spot under {@code id} (any case), or null when no layer authored one. */
    @Nullable
    public HudSpotAsset spot(@Nullable String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        return resolve(id.trim());
    }

    /**
     * Every spot a picker offers for the panel {@code panelId}: the enabled spots that name it
     * or name no panel, in listing order.
     */
    @Nonnull
    public List<HudSpotAsset> offeredFor(@Nonnull String panelId) {
        return all().values().stream()
                .filter(HudSpotAsset::enabled)
                .filter(spot -> spot.fits(panelId))
                .sorted(LISTING)
                .toList();
    }
}
