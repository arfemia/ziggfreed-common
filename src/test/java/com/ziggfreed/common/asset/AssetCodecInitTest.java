package com.ziggfreed.common.asset;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;

import com.ziggfreed.common.dialogue.asset.ZcDialogueAsset;
import com.ziggfreed.common.dialogue.asset.ZcDialogueTemplateAsset;
import com.ziggfreed.common.instance.effect.BandedEffectAsset;
import com.ziggfreed.common.instance.encounter.EncounterRuleAsset;
import com.ziggfreed.common.instance.encounter.MultiPhaseBossAsset;
import com.ziggfreed.common.instance.leaderboard.LeaderboardLayoutAsset;
import com.ziggfreed.common.instance.preset.InstancePresetAsset;
import com.ziggfreed.common.instance.reward.LootTableAsset;
import com.ziggfreed.common.party.PartySettingsAsset;
import com.ziggfreed.common.world.WeightedPrefabPlacementAsset;

/**
 * Forces each common framework asset CODEC to static-initialize, so a lower-case first
 * letter on any {@code KeyedCodec} field name (which the engine's {@code KeyedCodec}
 * constructor rejects at static init) fails the build here instead of at server start.
 * Mirrors hyMMO's {@code AssetCodecInitTest}. Every framework store registered by
 * {@code FrameworkAssetRegistrar} has its CODEC referenced here.
 */
class AssetCodecInitTest {

    @Test
    void instancePresetAssetCodecInitializes() {
        assertNotNull(InstancePresetAsset.CODEC, "InstancePresetAsset.CODEC must static-init (PascalCase keys)");
    }

    @Test
    void dialogueAssetCodecsInitialize() {
        assertNotNull(ZcDialogueAsset.CODEC, "ZcDialogueAsset.CODEC must static-init (PascalCase keys)");
        assertNotNull(ZcDialogueTemplateAsset.CODEC, "ZcDialogueTemplateAsset.CODEC must static-init (PascalCase keys)");
    }

    @Test
    void multiPhaseBossAssetCodecInitializes() {
        assertNotNull(MultiPhaseBossAsset.CODEC, "MultiPhaseBossAsset.CODEC must static-init (PascalCase keys)");
    }

    @Test
    void lootTableAssetCodecInitializes() {
        assertNotNull(LootTableAsset.CODEC, "LootTableAsset.CODEC must static-init (PascalCase keys)");
    }

    @Test
    void bandedEffectAssetCodecInitializes() {
        assertNotNull(BandedEffectAsset.CODEC, "BandedEffectAsset.CODEC must static-init (PascalCase keys)");
    }

    @Test
    void encounterRuleAssetCodecInitializes() {
        assertNotNull(EncounterRuleAsset.CODEC, "EncounterRuleAsset.CODEC must static-init (PascalCase keys)");
    }

    @Test
    void weightedPrefabPlacementAssetCodecInitializes() {
        assertNotNull(WeightedPrefabPlacementAsset.CODEC, "WeightedPrefabPlacementAsset.CODEC must static-init (PascalCase keys)");
    }

    @Test
    void leaderboardLayoutAssetCodecInitializes() {
        assertNotNull(LeaderboardLayoutAsset.CODEC, "LeaderboardLayoutAsset.CODEC must static-init (PascalCase keys)");
    }

    @Test
    void partySettingsAssetCodecInitializes() {
        assertNotNull(PartySettingsAsset.CODEC, "PartySettingsAsset.CODEC must static-init (PascalCase keys)");
    }
}
