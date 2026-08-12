package com.ziggfreed.common.asset;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;

import com.ziggfreed.common.dialogue.asset.DialogueOptionThemeAsset;
import com.ziggfreed.common.dialogue.asset.ZcDialogueAsset;
import com.ziggfreed.common.instance.effect.BandedEffectAsset;
import com.ziggfreed.common.instance.encounter.EncounterRuleAsset;
import com.ziggfreed.common.instance.encounter.MultiPhaseBossAsset;
import com.ziggfreed.common.instance.leaderboard.LeaderboardLayoutAsset;
import com.ziggfreed.common.instance.preset.InstancePresetAsset;
import com.ziggfreed.common.instance.reward.LootTableAsset;
import com.ziggfreed.common.loot.LootGrants;
import com.ziggfreed.common.loot.LootRef;
import com.ziggfreed.common.loot.LootableAsset;
import com.ziggfreed.common.loot.Roll;
import com.ziggfreed.common.loot.stamp.RollPoolAsset;
import com.ziggfreed.common.loot.stamp.StampSpec;
import com.ziggfreed.common.loot.stamp.StatRollEntry;
import com.ziggfreed.common.npc.placement.AppearanceSpec;
import com.ziggfreed.common.npc.NpcIdentityAsset;
import com.ziggfreed.common.npc.placement.NpcPlacementAsset;
import com.ziggfreed.common.factor.DerivedFactorAsset;
import com.ziggfreed.common.factor.FactorCondition;
import com.ziggfreed.common.factor.FactorFormula;
import com.ziggfreed.common.npc.placement.PlacedNpcComponent;
import com.ziggfreed.common.npc.placement.PlacementBinding;
import com.ziggfreed.common.party.PartySettingsAsset;
import com.ziggfreed.common.achievement.asset.AchievementAsset;
import com.ziggfreed.common.progress.asset.ContentTextAsset;
import com.ziggfreed.common.progress.asset.ObjectiveLeafAsset;
import com.ziggfreed.common.progress.asset.RewardEntryAsset;
import com.ziggfreed.common.progress.gate.GateClause;
import com.ziggfreed.common.progress.gate.GateSpec;
import com.ziggfreed.common.quest.asset.QuestAsset;
import com.ziggfreed.common.quest.asset.QuestGeneratorAsset;
import com.ziggfreed.common.quest.asset.QuestObjectiveAsset;
import com.ziggfreed.common.world.WeightedPrefabPlacementAsset;
import com.ziggfreed.common.world.WorldSelector;
import com.ziggfreed.common.world.WorldSelectorAsset;

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

    @Test
    void worldSelectorCodecsInitialize() {
        assertNotNull(WorldSelectorAsset.CODEC, "WorldSelectorAsset.CODEC must static-init (PascalCase keys)");
        // The embeddable group codec is not a store, but a consumer asset embeds it, so a
        // lower-case key here would fail at that consumer's decode instead of at this build.
        assertNotNull(WorldSelector.CODEC, "WorldSelector.CODEC must static-init (PascalCase keys)");
    }

    @Test
    void npcPlacementCodecsInitialize() {
        assertNotNull(NpcPlacementAsset.CODEC, "NpcPlacementAsset.CODEC must static-init (PascalCase keys)");
        // The two shared leaf codecs are not stores, but the asset embeds them, so a lower-case
        // key there would fail at that asset's decode instead of at this build.
        assertNotNull(PlacementBinding.CODEC, "PlacementBinding.CODEC must static-init (PascalCase keys)");
        assertNotNull(FactorCondition.CODEC, "FactorCondition.CODEC must static-init (PascalCase keys)");
        assertNotNull(FactorCondition.codec("ziggfreedcommon:placement_factors"),
                "the dropdown-bearing FactorCondition codec factory must static-init too");
        assertNotNull(PlacedNpcComponent.CODEC, "PlacedNpcComponent.CODEC must static-init (PascalCase keys)");
    }

    @Test
    void npcIdentityAssetCodecInitializes() {
        assertNotNull(NpcIdentityAsset.CODEC, "NpcIdentityAsset.CODEC must static-init (PascalCase keys)");
    }

    @Test
    void dialogueOptionThemeAssetCodecInitializes() {
        assertNotNull(DialogueOptionThemeAsset.CODEC, "DialogueOptionThemeAsset.CODEC must static-init (PascalCase keys)");
    }

    @Test
    void appearanceSpecCodecsInitialize() {
        // Embedded in NpcPlacementAsset.Identity rather than stored on its own, so without an
        // explicit assertion a lower-case key here would only surface at a consumer's decode.
        assertNotNull(AppearanceSpec.CODEC, "AppearanceSpec.CODEC must static-init (PascalCase keys)");
        assertNotNull(AppearanceSpec.ParticleSpec.CODEC,
                "AppearanceSpec.ParticleSpec.CODEC must static-init (PascalCase keys)");
        assertNotNull(AppearanceSpec.Rotation.CODEC,
                "AppearanceSpec.Rotation.CODEC must static-init (PascalCase keys)");
        assertNotNull(AppearanceSpec.Equipment.CODEC,
                "AppearanceSpec.Equipment.CODEC must static-init (PascalCase keys)");
    }

    @Test
    void derivedFactorCodecsInitialize() {
        assertNotNull(DerivedFactorAsset.CODEC, "DerivedFactorAsset.CODEC must static-init (PascalCase keys)");
        // The formula group is embedded rather than stored, at three nesting levels, so a lower-case
        // key at any of them would fail at a consumer's decode instead of at this build.
        assertNotNull(FactorFormula.CODEC, "FactorFormula.CODEC must static-init (PascalCase keys)");
        assertNotNull(FactorFormula.Term.CODEC, "FactorFormula.Term.CODEC must static-init (PascalCase keys)");
        assertNotNull(FactorFormula.Clamp.CODEC, "FactorFormula.Clamp.CODEC must static-init (PascalCase keys)");
        assertNotNull(FactorFormula.codec(EditorDataSets.FACTORS),
                "the dropdown-bearing FactorFormula codec factory must static-init too");
    }

    @Test
    void questCodecsInitialize() {
        assertNotNull(QuestAsset.CODEC, "QuestAsset.CODEC must static-init (PascalCase keys)");
        assertNotNull(QuestGeneratorAsset.CODEC, "QuestGeneratorAsset.CODEC must static-init (PascalCase keys)");
        // The nested groups and the two leaf types are embedded rather than stored, so a lower-case
        // key in any of them would fail at a consumer's decode instead of at this build.
        assertNotNull(ContentTextAsset.CODEC, "ContentTextAsset.CODEC must static-init (PascalCase keys)");
        assertNotNull(QuestAsset.Listing.CODEC, "QuestAsset.Listing.CODEC must static-init (PascalCase keys)");
        assertNotNull(QuestAsset.Flow.CODEC, "QuestAsset.Flow.CODEC must static-init (PascalCase keys)");
        assertNotNull(QuestAsset.Repeat.CODEC, "QuestAsset.Repeat.CODEC must static-init (PascalCase keys)");
        assertNotNull(QuestAsset.Visibility.CODEC, "QuestAsset.Visibility.CODEC must static-init (PascalCase keys)");
        assertNotNull(QuestAsset.Npc.CODEC, "QuestAsset.Npc.CODEC must static-init (PascalCase keys)");
        assertNotNull(QuestObjectiveAsset.CODEC, "QuestObjectiveAsset.CODEC must static-init (PascalCase keys)");
        assertNotNull(RewardEntryAsset.CODEC, "RewardEntryAsset.CODEC must static-init (PascalCase keys)");
        assertNotNull(GateClause.CODEC, "GateClause.CODEC must static-init (PascalCase keys)");
        assertNotNull(GateSpec.CODEC, "GateSpec.CODEC must static-init (PascalCase keys)");
        assertNotNull(QuestGeneratorAsset.Axis.CODEC,
                "QuestGeneratorAsset.Axis.CODEC must static-init (PascalCase keys)");
        assertNotNull(ObjectiveLeafAsset.CODEC,
                "ObjectiveLeafAsset.CODEC must static-init (PascalCase keys)");
    }

    @Test
    void achievementCodecsInitialize() {
        assertNotNull(AchievementAsset.CODEC, "AchievementAsset.CODEC must static-init (PascalCase keys)");
        // The nested groups are embedded rather than stored, so a lower-case key in either would fail
        // at a consumer's decode instead of at this build.
        assertNotNull(AchievementAsset.Listing.CODEC,
                "AchievementAsset.Listing.CODEC must static-init (PascalCase keys)");
        assertNotNull(AchievementAsset.Scoring.CODEC,
                "AchievementAsset.Scoring.CODEC must static-init (PascalCase keys)");
    }
    @Test
    void lootCodecsInitialize() {
        assertNotNull(LootableAsset.CODEC, "LootableAsset.CODEC must static-init (PascalCase keys)");
        assertNotNull(RollPoolAsset.CODEC, "RollPoolAsset.CODEC must static-init (PascalCase keys)");
        // The roll model nests five levels deep and every level is embedded rather than stored, so a
        // lower-case key at any of them would fail at a pack author's decode instead of at this build.
        assertNotNull(Roll.CODEC, "Roll.CODEC must static-init (PascalCase keys)");
        assertNotNull(Roll.Ladder.CODEC, "Roll.Ladder.CODEC must static-init (PascalCase keys)");
        assertNotNull(Roll.Ladder.Floor.CODEC, "Roll.Ladder.Floor.CODEC must static-init (PascalCase keys)");
        assertNotNull(LootGrants.CODEC, "LootGrants.CODEC must static-init (PascalCase keys)");
        assertNotNull(LootGrants.Item.CODEC, "LootGrants.Item.CODEC must static-init (PascalCase keys)");
        assertNotNull(LootGrants.Reward.CODEC, "LootGrants.Reward.CODEC must static-init (PascalCase keys)");
        assertNotNull(LootRef.CODEC, "LootRef.CODEC must static-init (PascalCase keys)");
        assertNotNull(StampSpec.CODEC, "StampSpec.CODEC must static-init (PascalCase keys)");
        assertNotNull(StampSpec.Picks.CODEC, "StampSpec.Picks.CODEC must static-init (PascalCase keys)");
        assertNotNull(StampSpec.Caps.CODEC, "StampSpec.Caps.CODEC must static-init (PascalCase keys)");
        assertNotNull(StampSpec.Budget.CODEC, "StampSpec.Budget.CODEC must static-init (PascalCase keys)");
        assertNotNull(StatRollEntry.CODEC, "StatRollEntry.CODEC must static-init (PascalCase keys)");
        assertNotNull(StatRollEntry.Points.CODEC, "StatRollEntry.Points.CODEC must static-init (PascalCase keys)");
        // The dropdown-bearing factories build a second codec tree; a key typo could hide in either.
        assertNotNull(Roll.codec(EditorDataSets.FACTORS),
                "the dropdown-bearing Roll codec factory must static-init too");
        assertNotNull(StampSpec.codec(EditorDataSets.FACTORS),
                "the dropdown-bearing StampSpec codec factory must static-init too");
    }
}
