package com.ziggfreed.common.ui.hud.bar;

import java.util.Comparator;
import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.asset.AbstractKeyedAssetConfig;

/**
 * The {@code defaults < pack < owner} fold of every {@link HudBarPlacementAsset}, keyed by
 * placement id.
 *
 * <p>The library ships its three spots in its own jar's asset pack, so they ride the PACK layer
 * and a consumer's same-id file replaces one by pack order; the owner layer, from
 * {@code mods/ziggfreedcommon/hud-bar-placements.json}, wins over both. A spot nobody authored
 * answers null, and every caller treats that as "sit where the document says".
 */
public final class HudBarPlacementConfig extends AbstractKeyedAssetConfig<HudBarPlacementAsset> {

    private static final HudBarPlacementConfig INSTANCE = new HudBarPlacementConfig();

    /** Pickers list by authored order, then id, so the list reads the same across reloads. */
    private static final Comparator<HudBarPlacementAsset> LISTING =
            Comparator.comparingInt(HudBarPlacementAsset::order)
                    .thenComparing(HudBarPlacementAsset::getId, String.CASE_INSENSITIVE_ORDER);

    private HudBarPlacementConfig() {
    }

    @Nonnull
    public static HudBarPlacementConfig getInstance() {
        return INSTANCE;
    }

    /** The folded placement under {@code id} (any case), or null when no layer authored one. */
    @Nullable
    public HudBarPlacementAsset placement(@Nullable String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        return resolve(id.trim());
    }

    /**
     * Every spot a picker offers for the panel {@code panelId}: the enabled placements that name it
     * or name no panel, in listing order.
     */
    @Nonnull
    public List<HudBarPlacementAsset> offeredFor(@Nonnull String panelId) {
        return all().values().stream()
                .filter(HudBarPlacementAsset::enabled)
                .filter(placement -> placement.fits(panelId))
                .sorted(LISTING)
                .toList();
    }
}
