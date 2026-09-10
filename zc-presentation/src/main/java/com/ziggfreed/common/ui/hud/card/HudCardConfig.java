package com.ziggfreed.common.ui.hud.card;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.asset.AbstractKeyedAssetConfig;

/**
 * The {@code defaults < pack < owner} fold of {@link HudCardAsset}, keyed by record id.
 *
 * <p>The library ships {@code Default.json} in its own jar's asset pack, so it rides the PACK
 * layer and a consumer's same-id file replaces it by pack order; the owner layer, from
 * {@code mods/ziggfreedcommon/hud-cards.json}, wins over both. Every card reads
 * {@link #sharedColor} per paint, so a reload lands on the next paint, and a server with no record
 * at all (nothing loaded yet, or the shipped file removed) reads null: the shipped look.
 */
public final class HudCardConfig extends AbstractKeyedAssetConfig<HudCardAsset> {

    private static final HudCardConfig INSTANCE = new HudCardConfig();

    private HudCardConfig() {
    }

    @Nonnull
    public static HudCardConfig getInstance() {
        return INSTANCE;
    }

    /** The folded record every card reads, or null when no layer authored one. */
    @Nullable
    public HudCardAsset shared() {
        return resolve(HudCardAsset.SHARED_ID);
    }

    /**
     * The colour the shared record states, normalised, or null for the shipped look: the record is
     * absent, states no colour, or states one that is not a hex. This is the layer under every
     * card's own leaf in {@link HudCardLook#resolve}.
     */
    @Nullable
    public String sharedColor() {
        HudCardAsset record = shared();
        return record == null ? null : record.color();
    }
}
