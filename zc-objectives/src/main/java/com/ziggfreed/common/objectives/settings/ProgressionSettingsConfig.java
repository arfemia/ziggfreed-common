package com.ziggfreed.common.objectives.settings;

import javax.annotation.Nonnull;

import com.ziggfreed.common.asset.AbstractKeyedAssetConfig;

/**
 * The {@code defaults < pack < owner} fold of {@link ProgressionSettingsAsset}, keyed by record id. The library ships
 * {@code Default.json} in its own jar's pack, so it rides the pack layer; the owner layer, from
 * {@code mods/ziggfreedcommon/progression.json}, wins over both. Before anything loads every read answers the
 * unauthored rules: no limit on the quest log.
 */
public final class ProgressionSettingsConfig extends AbstractKeyedAssetConfig<ProgressionSettingsAsset> {

    private static final ProgressionSettingsConfig INSTANCE = new ProgressionSettingsConfig();

    /** What a server with no record reads: every leaf unauthored. */
    private static final ProgressionSettingsAsset DEFAULTS = new ProgressionSettingsAsset();

    private ProgressionSettingsConfig() {
    }

    @Nonnull
    public static ProgressionSettingsConfig getInstance() {
        return INSTANCE;
    }

    /** The folded record every read uses, or the defaults when no layer authored one. */
    @Nonnull
    public ProgressionSettingsAsset settings() {
        return resolveOrDefault(ProgressionSettingsAsset.SHARED_ID, DEFAULTS);
    }

    /**
     * How many quests a player may carry at once right now, 0 for no limit: the quest engine's live cap, read on every
     * accept, so a change to the owner file counts at once.
     */
    public int maxActiveQuests() {
        return settings().quests().maxActive();
    }
}
