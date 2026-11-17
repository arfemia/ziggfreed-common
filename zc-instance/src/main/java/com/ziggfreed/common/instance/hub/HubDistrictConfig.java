package com.ziggfreed.common.instance.hub;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.asset.AbstractKeyedAssetConfig;

/**
 * The hub districts in force ({@link HubDistrictAsset}), folded {@code defaults < pack < owner} like every keyed asset
 * type: the pack layer from the store's own load event (a file gated on a missing mod left out there), then the
 * owner's {@code mods/ziggfreedcommon/hub-districts.json} read on the same event ({@link HubDistrictOwnerLayers}).
 * Ids are lower-cased on every layer.
 *
 * <p>Read it lazily, after {@code LoadedAssetsEvent}: nothing here is meaningful at setup. A hub copy reads it once,
 * as it opens ({@link HubPlan}).
 */
public final class HubDistrictConfig extends AbstractKeyedAssetConfig<HubDistrictAsset> {

    /** The store's mod-gate label, which its drop lines carry. */
    public static final String MOD_GATE_STORE = "HubDistricts";

    private static final HubDistrictConfig INSTANCE = new HubDistrictConfig();

    private HubDistrictConfig() {
        super(MOD_GATE_STORE);
    }

    @Nonnull
    public static HubDistrictConfig getInstance() {
        return INSTANCE;
    }

    /**
     * Every enabled district as the layout reads it ({@link HubLayout.Claim}): with {@code seasonNow}, only those whose
     * season runs now (a copy opening), else every season's (the audit, so a seasonal clash shows all year). A
     * district the owner's file moved to another lot than its pack named claims first.
     */
    @Nonnull
    public List<HubLayout.Claim> claims(boolean seasonNow) {
        Map<String, HubDistrictAsset> packs = packLayer();
        Map<String, HubDistrictAsset> owner = ownerLayer();
        List<HubLayout.Claim> out = new ArrayList<>();
        for (Map.Entry<String, HubDistrictAsset> entry : all().entrySet()) {
            HubDistrictAsset district = entry.getValue();
            if (district == null || !district.isEnabled() || (seasonNow && !district.isLive())) {
                continue;
            }
            HubDistrictAsset fromOwner = owner.get(entry.getKey());
            HubDistrictAsset fromPack = packs.get(entry.getKey());
            boolean ownerPlaced = fromOwner != null && fromOwner.lotId() != null
                    && (fromPack == null || !Objects.equals(lower(fromOwner.lotId()), lower(fromPack.lotId())));
            out.add(new HubLayout.Claim(idOf(entry.getKey(), district), district.lotId(), district.size(),
                    district.fillsEmpty(), ownerPlaced));
        }
        return out;
    }

    /** A district's id as its file spells it, else the fold's lower-cased key. */
    @Nonnull
    private static String idOf(@Nonnull String key, @Nonnull HubDistrictAsset district) {
        return district.getId() == null ? key : district.getId();
    }

    @Nullable
    private static String lower(@Nullable String id) {
        return id == null ? null : id.toLowerCase(Locale.ROOT);
    }
}
