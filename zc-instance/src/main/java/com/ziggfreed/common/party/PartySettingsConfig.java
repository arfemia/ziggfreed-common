package com.ziggfreed.common.party;

import javax.annotation.Nonnull;

import com.ziggfreed.common.asset.AbstractKeyedAssetConfig;

/**
 * The {@code defaults < pack < owner} fold authority for {@link PartyConfig} party-policy
 * settings, keyed by asset id - the authorable home of the previously-hardcoded
 * {@code new PartyConfig(4, 60, false)} Java literal a {@link PartyService} consumed.
 *
 * <p>The fold mechanics (the three layers, idempotent re-import, resolve order) live in
 * the shared {@link AbstractKeyedAssetConfig} base; this singleton adds only the
 * {@link PartyConfig} type binding and {@link #getInstance()}. The map holds the RESOLVED
 * {@link PartyConfig} as its value (the {@code PartySettingsAsset} merge mapper calls
 * {@code (id, a) -> a.toConfig()}). A consumer resolves a setting by id with a baked-in
 * fallback, e.g. {@code resolveOrDefault(id, new PartyConfig(4, 60, false))}.
 */
public final class PartySettingsConfig extends AbstractKeyedAssetConfig<PartyConfig> {

    private static final PartySettingsConfig INSTANCE = new PartySettingsConfig();

    @Nonnull
    public static PartySettingsConfig getInstance() {
        return INSTANCE;
    }

    private PartySettingsConfig() {
    }
}
