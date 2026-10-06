package com.ziggfreed.common.settings;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.asset.AbstractKeyedAssetConfig;

/**
 * The {@code defaults < pack < owner} fold of {@link PlayerSettingsAsset}, keyed by record id. The library
 * ships {@code Default.json} in its own jar's pack, so it rides the PACK layer; the owner layer, from
 * {@code mods/ziggfreedcommon/player-settings.json}, wins over both. Before anything loads every read
 * answers the unauthored rules: the tracker shows, the level is every update, nothing is fixed.
 */
public final class PlayerSettingsConfig extends AbstractKeyedAssetConfig<PlayerSettingsAsset> {

    private static final PlayerSettingsConfig INSTANCE = new PlayerSettingsConfig();

    private PlayerSettingsConfig() {
    }

    @Nonnull
    public static PlayerSettingsConfig getInstance() {
        return INSTANCE;
    }

    /** The folded record every read uses, or null when no layer authored one. */
    @Nullable
    public PlayerSettingsAsset shared() {
        return resolve(PlayerSettingsAsset.SHARED_ID);
    }

    /** The quest tracker's rules, never null. */
    @Nonnull
    public SurfaceRules questTracker() {
        PlayerSettingsAsset record = shared();
        return record == null ? SurfaceRules.NONE : record.questTracker();
    }

    /** The notification level's rules, never null. */
    @Nonnull
    public NotificationRules.LevelRule level() {
        PlayerSettingsAsset record = shared();
        return record == null ? NotificationRules.LevelRule.NONE : record.notifications().level();
    }
}
