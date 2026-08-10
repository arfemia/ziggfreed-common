package com.ziggfreed.common.instance.encounter;

import javax.annotation.Nonnull;

import com.ziggfreed.common.asset.AbstractKeyedAssetConfig;

/**
 * The {@code defaults < pack < owner} fold authority for {@link EncounterRuleAsset}s, keyed
 * by lowercase rule id - the generic, mod-agnostic config that feeds an
 * {@link EncounterDirector} wave gate. A consumer authors its rules as pack JSON under
 * {@code Server/ZiggfreedCommon/EncounterRules/*.json}, common registers the store, and the
 * consumer reads the effective rules back through this singleton ({@link #resolve},
 * {@link #all}), filters by {@link EncounterRuleAsset#trigger()}, and fires the matching
 * rules through its own {@link EncounterDirector} on the world thread.
 *
 * <p>The fold mechanics (the three layers, lower-casing, idempotent re-import, resolve
 * order) live in the shared {@link AbstractKeyedAssetConfig} base; this singleton adds only
 * the {@link EncounterRuleAsset} type binding and {@link #getInstance()}. The config holds
 * the ASSET itself as {@code T} (the consumer reads its getters directly, like Kweebec's
 * {@code SpawnRuleConfig} reads a {@code SpawnRuleAsset}), so the merge layer maps each
 * decoded entry straight through ({@code (id, a) -> a}); there is no resolved-model twin.
 * A consumer that wants a runtime (4th) override tier holds it itself, composing it above
 * {@link #resolve}.
 */
public final class EncounterRuleConfig extends AbstractKeyedAssetConfig<EncounterRuleAsset> {

    private static final EncounterRuleConfig INSTANCE = new EncounterRuleConfig();

    @Nonnull
    public static EncounterRuleConfig getInstance() {
        return INSTANCE;
    }

    private EncounterRuleConfig() {
    }
}
