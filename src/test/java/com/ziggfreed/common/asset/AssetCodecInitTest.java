package com.ziggfreed.common.asset;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import com.ziggfreed.common.achievement.asset.AchievementAsset;
import com.ziggfreed.common.achievement.asset.AchievementCategoryAsset;
import com.ziggfreed.common.achievement.asset.AchievementMilestoneAsset;
import com.ziggfreed.common.board.asset.BoardAsset;
import com.ziggfreed.common.board.asset.BoardSlotAsset;
import com.ziggfreed.common.board.asset.BountyAsset;
import com.ziggfreed.common.commerce.asset.CostAsset;
import com.ziggfreed.common.commerce.asset.RerollAsset;
import com.ziggfreed.common.commerce.asset.RotationAsset;
import com.ziggfreed.common.commerce.asset.SelectionAsset;
import com.ziggfreed.common.commerce.asset.SlotAsset;
import com.ziggfreed.common.commerce.fold.CommerceDestinations;
import com.ziggfreed.common.currency.asset.CurrencyAsset;
import com.ziggfreed.common.dialogue.state.DialogueFlagScope;
import com.ziggfreed.common.dialogue.state.DialogueMemory;
import com.ziggfreed.common.dialogue.state.DialogueOnce;
import com.ziggfreed.common.dialogue.schema.DialogueStart;
import com.ziggfreed.common.dialogue.asset.DialogueFragmentAsset;
import com.ziggfreed.common.dialogue.asset.DialogueOptionThemeAsset;
import com.ziggfreed.common.dialogue.asset.ZcDialogueAsset;
import com.ziggfreed.common.encounter.asset.EncounterBindingAsset;
import com.ziggfreed.common.encounter.asset.EncounterParticipationAsset;
import com.ziggfreed.common.factor.DerivedFactorAsset;
import com.ziggfreed.common.factor.FactorCondition;
import com.ziggfreed.common.factor.FactorFormula;
import com.ziggfreed.common.feedback.moment.FeedbackMomentAsset;
import com.ziggfreed.common.instance.effect.BandedEffectAsset;
import com.ziggfreed.common.instance.leaderboard.LeaderboardLayoutAsset;
import com.ziggfreed.common.instance.preset.InstancePresetAsset;
import com.ziggfreed.common.loot.LootGrants;
import com.ziggfreed.common.loot.LootRef;
import com.ziggfreed.common.loot.LootableAsset;
import com.ziggfreed.common.loot.Roll;
import com.ziggfreed.common.loot.reward.RewardKindAsset;
import com.ziggfreed.common.loot.stamp.RollPoolAsset;
import com.ziggfreed.common.loot.stamp.StampSpec;
import com.ziggfreed.common.loot.stamp.StatRollEntry;
import com.ziggfreed.common.npc.NpcDestinations;
import com.ziggfreed.common.npc.NpcIdentityAsset;
import com.ziggfreed.common.npc.placement.asset.NpcPlacementAsset;
import com.ziggfreed.common.npc.placement.runtime.PlacedNpcComponent;
import com.ziggfreed.common.objectives.store.ZigProgressComponent;
import com.ziggfreed.common.party.PartySettingsAsset;
import com.ziggfreed.common.progress.asset.ContentRewardsAsset;
import com.ziggfreed.common.progress.asset.GeneratorAxisAsset;
import com.ziggfreed.common.progress.asset.ObjectiveLeafAsset;
import com.ziggfreed.common.progress.asset.RewardEntryAsset;
import com.ziggfreed.common.progress.gate.GateClause;
import com.ziggfreed.common.progress.gate.GateSpec;
import com.ziggfreed.common.quest.asset.QuestAsset;
import com.ziggfreed.common.quest.asset.QuestGeneratorAsset;
import com.ziggfreed.common.quest.asset.QuestObjectiveAsset;
import com.ziggfreed.common.shop.asset.PoolSlotAsset;
import com.ziggfreed.common.shop.asset.ShopEntryAsset;
import com.ziggfreed.common.shop.asset.ShopEntryGeneratorAsset;
import com.ziggfreed.common.shop.asset.ShopPoolAsset;
import com.ziggfreed.common.shop.asset.StorefrontAsset;
import com.ziggfreed.common.text.ContentTextAsset;
import com.ziggfreed.common.ui.route.Destination;
import com.ziggfreed.common.world.WeightedPrefabPlacementAsset;
import com.ziggfreed.common.world.WorldSelector;

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
        assertNotNull(DialogueFragmentAsset.CODEC,
                "DialogueFragmentAsset.CODEC must static-init (PascalCase keys)");
        // The conversation's own leaf codecs are not stores, but a lower-case key in one would fail
        // at a conversation's decode rather than here, which is far later and much harder to place.
        assertNotNull(DialogueStart.Variant.CODEC,
                "the Pick variant codec must static-init (PascalCase keys)");
        assertNotNull(DialogueStart.Variant.WEIGHT_CODEC,
                "a variant's number-or-formula weight codec must static-init");
        assertNotNull(DialogueStart.QuestRow.CODEC,
                "the Start quest-row codec must static-init (PascalCase keys)");
        assertNotNull(DialogueStart.QuestBeat.CODEC, "the quest-row beat codec must static-init");
        assertNotNull(DialogueFlagScope.CODEC,
                "the per-world scope codec must static-init (PascalCase keys)");
        assertNotNull(DialogueOnce.CODEC, "the Once codec must static-init (PascalCase keys)");
        assertNotNull(DialogueMemory.CODEC, "the Memories codec must static-init (PascalCase keys)");
    }

    @Test
    void encounterBindingAssetCodecInitializes() {
        assertNotNull(EncounterBindingAsset.CODEC, "EncounterBindingAsset.CODEC must static-init (PascalCase keys)");
        assertNotNull(EncounterBindingAsset.Subject.CODEC, "the Subject group codec must static-init");
        assertNotNull(EncounterBindingAsset.Participation.CODEC, "the Participation group codec must static-init");
        assertNotNull(EncounterBindingAsset.Scale.CODEC, "the Scale group codec must static-init");
        assertNotNull(EncounterBindingAsset.Timing.CODEC, "the Timing group codec must static-init");
        assertNotNull(EncounterBindingAsset.Loot.CODEC, "the Loot group codec must static-init");
        assertNotNull(EncounterBindingAsset.Leaderboard.CODEC, "the Leaderboard group codec must static-init");
        assertNotNull(EncounterBindingAsset.Progression.CODEC, "the Progression group codec must static-init");
        assertNotNull(EncounterBindingAsset.Feedback.CODEC, "the Feedback group codec must static-init");
        assertNotNull(EncounterBindingAsset.Discovery.CODEC, "the Discovery group codec must static-init");
    }

    @Test
    void encounterParticipationAssetCodecInitializes() {
        assertNotNull(EncounterParticipationAsset.CODEC,
                "EncounterParticipationAsset.CODEC must static-init (PascalCase keys)");
    }

    @Test
    void bandedEffectAssetCodecInitializes() {
        assertNotNull(BandedEffectAsset.CODEC, "BandedEffectAsset.CODEC must static-init (PascalCase keys)");
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
    void worldSelectorCodecInitializes() {
        // The embeddable Where group codec is not a store, but a consumer asset embeds it, so a
        // lower-case key here would fail at that consumer's decode instead of at this build.
        assertNotNull(WorldSelector.CODEC, "WorldSelector.CODEC must static-init (PascalCase keys)");
    }

    @Test
    void npcPlacementCodecsInitialize() {
        assertNotNull(NpcPlacementAsset.CODEC, "NpcPlacementAsset.CODEC must static-init (PascalCase keys)");
        // The shared leaf codecs are not stores, but the asset embeds them, so a lower-case key
        // there would fail at that asset's decode instead of at this build.
        assertNotNull(Destination.CODEC, "Destination.CODEC must static-init");
        assertNotNull(NpcDestinations.Dialogue.CODEC,
                "the seeded Dialogue destination's codec must static-init (PascalCase keys)");
        assertNotNull(NpcDestinations.Quests.CODEC,
                "the seeded Quests destination's codec must static-init (PascalCase keys)");
        assertNotNull(FactorCondition.CODEC, "FactorCondition.CODEC must static-init (PascalCase keys)");
        assertNotNull(FactorCondition.codec("ziggfreedcommon:placement_factors"),
                "the dropdown-bearing FactorCondition codec factory must static-init too");
        assertNotNull(PlacedNpcComponent.CODEC, "PlacedNpcComponent.CODEC must static-init (PascalCase keys)");
    }

    @Test
    void standaloneProgressComponentCodecInitializes() {
        assertNotNull(ZigProgressComponent.CODEC,
                "ZigProgressComponent.CODEC must static-init (PascalCase keys)");
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
    void feedbackMomentCodecsInitialize() {
        assertNotNull(FeedbackMomentAsset.CODEC, "FeedbackMomentAsset.CODEC must static-init (PascalCase keys)");
        // The four groups and their shared line leaf are embedded, so a lower-case key at any level
        // would fail at a consumer's decode instead of at this build.
        assertNotNull(FeedbackMomentAsset.Line.CODEC, "FeedbackMomentAsset.Line.CODEC must static-init (PascalCase keys)");
        assertNotNull(FeedbackMomentAsset.Toast.CODEC, "FeedbackMomentAsset.Toast.CODEC must static-init (PascalCase keys)");
        assertNotNull(FeedbackMomentAsset.Broadcast.CODEC, "FeedbackMomentAsset.Broadcast.CODEC must static-init (PascalCase keys)");
        assertNotNull(FeedbackMomentAsset.Sound.CODEC, "FeedbackMomentAsset.Sound.CODEC must static-init (PascalCase keys)");
        assertNotNull(FeedbackMomentAsset.Command.CODEC, "FeedbackMomentAsset.Command.CODEC must static-init (PascalCase keys)");
        assertNotNull(FeedbackMomentAsset.Variant.CODEC, "FeedbackMomentAsset.Variant.CODEC must static-init (PascalCase keys)");
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
        assertNotNull(QuestAsset.Repeat.Reset.CODEC,
                "QuestAsset.Repeat.Reset.CODEC must static-init (PascalCase keys)");
        assertNotNull(QuestAsset.Npc.CODEC, "QuestAsset.Npc.CODEC must static-init (PascalCase keys)");
        assertNotNull(QuestObjectiveAsset.CODEC, "QuestObjectiveAsset.CODEC must static-init (PascalCase keys)");
        assertNotNull(RewardEntryAsset.CODEC, "RewardEntryAsset.CODEC must static-init (PascalCase keys)");
        assertNotNull(GateClause.CODEC, "GateClause.CODEC must static-init (PascalCase keys)");
        assertNotNull(GateSpec.CODEC, "GateSpec.CODEC must static-init (PascalCase keys)");
        assertNotNull(GeneratorAxisAsset.CODEC,
                "GeneratorAxisAsset.CODEC must static-init (PascalCase keys)");
        assertNotNull(ObjectiveLeafAsset.CODEC,
                "ObjectiveLeafAsset.CODEC must static-init (PascalCase keys)");
    }

    @Test
    void commerceGroupCodecsInitialize() {
        // Shared by every commerce type and embedded rather than stored, so a lower-case key in any
        // of them would fail at a storefront's or a board's decode instead of at this build.
        assertNotNull(CostAsset.CODEC, "CostAsset.CODEC must static-init (PascalCase keys)");
        assertNotNull(CostAsset.ItemCostAsset.CODEC,
                "CostAsset.ItemCostAsset.CODEC must static-init (PascalCase keys)");
        assertNotNull(RotationAsset.CODEC, "RotationAsset.CODEC must static-init (PascalCase keys)");
        assertNotNull(SelectionAsset.CODEC, "SelectionAsset.CODEC must static-init (PascalCase keys)");
        assertNotNull(SlotAsset.CODEC, "SlotAsset.CODEC must static-init (PascalCase keys)");
        assertNotNull(RerollAsset.CODEC, "RerollAsset.CODEC must static-init (PascalCase keys)");
    }

    @Test
    void currencyAssetCodecsInitialize() {
        assertNotNull(CurrencyAsset.CODEC, "CurrencyAsset.CODEC must static-init (PascalCase keys)");
        assertNotNull(CurrencyAsset.Backing.CODEC,
                "CurrencyAsset.Backing.CODEC must static-init (PascalCase keys)");
        assertNotNull(CurrencyAsset.OnDeath.CODEC,
                "CurrencyAsset.OnDeath.CODEC must static-init (PascalCase keys)");
        assertNotNull(CurrencyAsset.Decay.CODEC, "CurrencyAsset.Decay.CODEC must static-init (PascalCase keys)");
    }

    @Test
    void shopCodecsInitialize() {
        assertNotNull(StorefrontAsset.CODEC, "StorefrontAsset.CODEC must static-init (PascalCase keys)");
        assertNotNull(ShopPoolAsset.CODEC, "ShopPoolAsset.CODEC must static-init (PascalCase keys)");
        assertNotNull(PoolSlotAsset.CODEC, "PoolSlotAsset.CODEC must static-init (PascalCase keys)");
        assertNotNull(ShopEntryAsset.CODEC, "ShopEntryAsset.CODEC must static-init (PascalCase keys)");
        assertNotNull(ShopEntryGeneratorAsset.CODEC,
                "ShopEntryGeneratorAsset.CODEC must static-init (PascalCase keys)");
        // The nested groups are embedded rather than stored, so a lower-case key in any of them would
        // fail at an offer's decode instead of at this build.
        assertNotNull(ShopEntryAsset.Listing.CODEC,
                "ShopEntryAsset.Listing.CODEC must static-init (PascalCase keys)");
        assertNotNull(ShopEntryAsset.Limits.CODEC,
                "ShopEntryAsset.Limits.CODEC must static-init (PascalCase keys)");
        assertNotNull(ShopEntryAsset.PoolMembership.CODEC,
                "ShopEntryAsset.PoolMembership.CODEC must static-init (PascalCase keys)");
    }

    @Test
    void commerceDestinationCodecsInitialize() {
        // Neither is a store, but a placement, a conversation option and a block all embed one, so a
        // lower-case key here would fail at that content's decode instead of at this build.
        assertNotNull(CommerceDestinations.Shop.CODEC,
                "the seeded Shop destination's codec must static-init (PascalCase keys)");
        assertNotNull(CommerceDestinations.Board.CODEC,
                "the seeded Board destination's codec must static-init (PascalCase keys)");
    }

    @Test
    void boardCodecsInitialize() {
        assertNotNull(BoardAsset.CODEC, "BoardAsset.CODEC must static-init (PascalCase keys)");
        assertNotNull(BoardSlotAsset.CODEC, "BoardSlotAsset.CODEC must static-init (PascalCase keys)");
        assertNotNull(BountyAsset.CODEC, "BountyAsset.CODEC must static-init (PascalCase keys)");
        assertNotNull(BountyAsset.Listing.CODEC,
                "BountyAsset.Listing.CODEC must static-init (PascalCase keys)");
        assertNotNull(BountyAsset.BoardMembership.CODEC,
                "BountyAsset.BoardMembership.CODEC must static-init (PascalCase keys)");
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
        assertNotNull(AchievementCategoryAsset.CODEC,
                "AchievementCategoryAsset.CODEC must static-init (PascalCase keys)");
        assertNotNull(AchievementMilestoneAsset.CODEC,
                "AchievementMilestoneAsset.CODEC must static-init (PascalCase keys)");
        assertNotNull(ContentRewardsAsset.CODEC,
                "ContentRewardsAsset.CODEC must static-init (PascalCase keys)");
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

    @Test
    void rewardKindCodecsInitialize() {
        assertNotNull(RewardKindAsset.CODEC, "RewardKindAsset.CODEC must static-init (PascalCase keys)");
        // The parameter declaration is a nested group rather than a store of its own, so a lower-case
        // key there would fail at an author's decode instead of at this build.
        assertNotNull(RewardKindAsset.Param.CODEC,
                "RewardKindAsset.Param.CODEC must static-init (PascalCase keys)");
    }

    /**
     * A reward kind is the one framework type where registering the store is only half the wiring.
     * The decoded files land in {@code RewardKindConfig}, and nothing there is payable until
     * {@code RewardKindFold} turns it into a registered handler - so a registrar that registers the
     * store and forgets the fold produces a server where every authored kind decodes cleanly, appears
     * in the config, and pays out nothing. That failure is silent at boot and only shows up as a
     * reward a player never received, which is why it is pinned here rather than left to review.
     */
    @Test
    void theRewardKindStoreIsFoldedAndNotJustRegistered() throws IOException {
        Path registrar = Path.of("src", "main", "java", "com", "ziggfreed", "common", "asset",
                "FrameworkAssetRegistrar.java");
        assertTrue(Files.isRegularFile(registrar), "missing root source: " + registrar.toAbsolutePath());
        String source = Files.readString(registrar, StandardCharsets.UTF_8);

        assertTrue(source.contains("RewardKindAsset.class"),
                "the RewardKinds store must be registered like every other framework type");
        assertTrue(source.contains("RewardKindFold.foldInto"),
                "an authored kind that is never folded decodes fine and pays out nothing");
    }
}
