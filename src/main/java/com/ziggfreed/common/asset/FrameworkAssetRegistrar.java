package com.ziggfreed.common.asset;

import javax.annotation.Nonnull;

import com.hypixel.hytale.assetstore.event.LoadedAssetsEvent;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.ziggfreed.common.CommonLog;
import com.ziggfreed.common.ZiggfreedCommonPlugin;
import com.ziggfreed.common.dialogue.DialogueOptionThemeConfig;
import com.ziggfreed.common.dialogue.asset.DialogueAssetStore;
import com.ziggfreed.common.dialogue.asset.DialogueOptionThemeAsset;
import com.ziggfreed.common.dialogue.asset.ZcDialogueAsset;
import com.ziggfreed.common.factor.DerivedFactorAsset;
import com.ziggfreed.common.factor.DerivedFactorConfig;
import com.ziggfreed.common.factor.FactorFormula;
import com.ziggfreed.common.instance.arena.ArenaDefinitionAsset;
import com.ziggfreed.common.instance.arena.ArenaDefinitionConfig;
import com.ziggfreed.common.instance.effect.BandedEffectAsset;
import com.ziggfreed.common.instance.effect.BandedEffectConfig;
import com.ziggfreed.common.instance.encounter.EncounterRuleAsset;
import com.ziggfreed.common.instance.encounter.EncounterRuleConfig;
import com.ziggfreed.common.instance.encounter.MultiPhaseBossAsset;
import com.ziggfreed.common.instance.encounter.MultiPhaseBossConfig;
import com.ziggfreed.common.instance.leaderboard.LeaderboardLayoutAsset;
import com.ziggfreed.common.instance.leaderboard.LeaderboardLayoutConfig;
import com.ziggfreed.common.instance.preset.InstancePresetAsset;
import com.ziggfreed.common.instance.preset.InstancePresetConfig;
import com.ziggfreed.common.loot.LootableAsset;
import com.ziggfreed.common.loot.LootableConfig;
import com.ziggfreed.common.loot.reward.RewardKindAsset;
import com.ziggfreed.common.loot.reward.RewardKindConfig;
import com.ziggfreed.common.loot.reward.RewardKindFold;
import com.ziggfreed.common.loot.reward.RewardKinds;
import com.ziggfreed.common.loot.stamp.RollPoolAsset;
import com.ziggfreed.common.loot.stamp.RollPoolConfig;
import com.ziggfreed.common.npc.NpcIdentityAsset;
import com.ziggfreed.common.npc.NpcIdentityConfig;
import com.ziggfreed.common.npc.placement.NpcPlacementAsset;
import com.ziggfreed.common.npc.placement.NpcPlacementConfig;
import com.ziggfreed.common.party.PartySettingsAsset;
import com.ziggfreed.common.party.PartySettingsConfig;
import com.ziggfreed.common.achievement.asset.AchievementAsset;
import com.ziggfreed.common.achievement.asset.AchievementAssetStore;
import com.ziggfreed.common.achievement.asset.AchievementCategoryAsset;
import com.ziggfreed.common.achievement.asset.AchievementCategoryConfig;
import com.ziggfreed.common.achievement.asset.AchievementMilestoneAsset;
import com.ziggfreed.common.achievement.asset.AchievementMilestoneConfig;
import com.ziggfreed.common.quest.asset.QuestAsset;
import com.ziggfreed.common.quest.asset.QuestAssetStore;
import com.ziggfreed.common.quest.asset.QuestGeneratorAsset;
import com.ziggfreed.common.world.WeightedPrefabPlacementAsset;
import com.ziggfreed.common.world.WeightedPrefabPlacementConfig;
import com.ziggfreed.common.world.WorldIdentity;
import com.ziggfreed.common.world.WorldSelectorAsset;
import com.ziggfreed.common.world.WorldSelectorConfig;

/**
 * The ONE registrar for ziggfreed-common's framework asset stores, called once from
 * {@link ZiggfreedCommonPlugin#setup()}. Common OWNS these stores: it registers each
 * asset class exactly once at {@code Server/ZiggfreedCommon/<Type>/} and wires the
 * single {@code LoadedAssetsEvent} merge listener that folds the loaded entries into the
 * common config singletons. A consumer mod (Kweebec, a future dungeon) authors JSON into
 * those paths and READS the resolved config back; it must NOT re-register these classes
 * (Hytale's {@code AssetRegistry} keys stores by class and throws on a duplicate).
 *
 * <p>Common ships no jar CONTENT for these stores (content is consumer pack JSON), so there
 * is no add/replace pack-control gate: a later pack's same-id file simply wins
 * (last-pack-wins by id). Two store types are a deliberate exception, and both ship
 * STRUCTURE rather than content: {@code DialogueOptionTheme} (the neutral look per option
 * kind, so a page renders before anyone authors a theme) and {@code WorldSelectors} (the
 * {@code default} and {@code any} names, which are the vocabulary every other selector-aware
 * file is written against - a consumer would have to re-declare them in every pack
 * otherwise). Both ride the jar's own asset pack, so an owner overrides either by dropping a
 * same-id file, and world selectors additionally take an owner layer at
 * {@code mods/ziggfreedcommon/world-selectors.json}.
 *
 * <p><b>REGISTRATION ONLY (build-enforced).</b> This registrar reaches into every domain, which is
 * exactly why it must never grow a decision: whatever lands here is unreachable from any module's
 * own tests and welds the domains together through the back door. A store registration, its merge
 * listener, and the id/order wiring that pairs them are the whole remit; anything that has to
 * CHOOSE belongs in the owning module behind a seam. {@code RootRegistrationOnlyTest} fails the
 * build on a loop, a {@code switch} or an {@code else} here; a try/catch guard, a
 * null-or-early-return {@code if}, and a null-defaulting ternary all pass. The escape hatch is
 * {@code // ROOT-LOGIC-OK: <reason>} with a real reason.
 */
public final class FrameworkAssetRegistrar {

    private FrameworkAssetRegistrar() {
    }

    /** Register every framework store + its merge listener. Call once from {@code setup()}. */
    public static void registerAll(@Nonnull JavaPlugin plugin) {
        // --- Dialogues (Pattern A) - one authored conversation per file, with native Parent
        //     inheritance and a per-screen merge, so a child conversation restates one screen and
        //     keeps the rest. Common ships no dialogue CONTENT; every entry is consumer pack JSON,
        //     and each consumer reads back only its own via DialogueAssetStore.dialogues(owner). ---
        AssetStoreRegistrar.registerStore(ZcDialogueAsset.class,
                new DefaultAssetMap<String, ZcDialogueAsset>(), "ZiggfreedCommon/Dialogues",
                ZcDialogueAsset::getId, ZcDialogueAsset.CODEC, null);
        plugin.getEventRegistry().register(LoadedAssetsEvent.class, ZcDialogueAsset.class,
                (LoadedAssetsEvent<String, ZcDialogueAsset, DefaultAssetMap<String, ZcDialogueAsset>> ev) ->
                        DialogueAssetStore.getInstance().merge(
                                AssetMergeAdapter.layer(ev.getAssetMap(), (id, a) -> a)));

        // --- Instance presets (the cross-cutting preset layer, relocated to common-owned). ---
        AssetStoreRegistrar.registerStore(InstancePresetAsset.class,
                new DefaultAssetMap<String, InstancePresetAsset>(), "ZiggfreedCommon/Instances",
                InstancePresetAsset::getId, InstancePresetAsset.CODEC, null);
        plugin.getEventRegistry().register(LoadedAssetsEvent.class, InstancePresetAsset.class,
                (LoadedAssetsEvent<String, InstancePresetAsset, DefaultAssetMap<String, InstancePresetAsset>> ev) ->
                        InstancePresetConfig.getInstance().mergePackLayer(
                                AssetMergeAdapter.layer(ev.getAssetMap(), (id, a) -> a.toPreset(id))));

        // --- Lootables (Pattern A) - named, reusable conditional loot tables anything can reference
        //     by id, including the score-tiered pools an instance preset names. Common ships no loot
        //     CONTENT; every table is consumer pack JSON. ---
        AssetStoreRegistrar.registerStore(LootableAsset.class,
                new DefaultAssetMap<String, LootableAsset>(), LootableAsset.TYPE_ROOT,
                LootableAsset::getId, LootableAsset.CODEC, null);
        plugin.getEventRegistry().register(LoadedAssetsEvent.class, LootableAsset.class,
                (LoadedAssetsEvent<String, LootableAsset, DefaultAssetMap<String, LootableAsset>> ev) ->
                        LootableConfig.getInstance().mergePackLayer(AssetMergeAdapter.layer(ev.getAssetMap())));

        // --- Roll pools (Pattern A) - named, reusable stat-roll tables a stamp draws from. ---
        AssetStoreRegistrar.registerStore(RollPoolAsset.class,
                new DefaultAssetMap<String, RollPoolAsset>(), RollPoolAsset.TYPE_ROOT,
                RollPoolAsset::getId, RollPoolAsset.CODEC, null);
        plugin.getEventRegistry().register(LoadedAssetsEvent.class, RollPoolAsset.class,
                (LoadedAssetsEvent<String, RollPoolAsset, DefaultAssetMap<String, RollPoolAsset>> ev) ->
                        RollPoolConfig.getInstance().mergePackLayer(AssetMergeAdapter.layer(ev.getAssetMap())));

        // --- Reward kinds (Pattern A) - a reward KIND written as a file: a declared parameter
        //     schema plus one console command line, so a server with an admin command it wants paid
        //     out as a reward needs no plugin to say so. Folding is part of the SAME listener on
        //     purpose. The fold has to run after every Java registration (a consumer's setup() is
        //     long over by the time assets load) and after the layers resolve, and splitting it into
        //     a second listener for the same event would leave that order to listener registration
        //     order. JSON WINS here: an authored id replaces a Java-registered one, loudly, once. ---
        AssetStoreRegistrar.registerStore(RewardKindAsset.class,
                new DefaultAssetMap<String, RewardKindAsset>(), RewardKindAsset.TYPE_ROOT,
                RewardKindAsset::getId, RewardKindAsset.CODEC, null);
        plugin.getEventRegistry().register(LoadedAssetsEvent.class, RewardKindAsset.class,
                (LoadedAssetsEvent<String, RewardKindAsset, DefaultAssetMap<String, RewardKindAsset>> ev) -> {
                    RewardKindConfig.getInstance().mergePackLayer(AssetMergeAdapter.layer(ev.getAssetMap()));
                    RewardKindFold.foldInto(RewardKinds.shared());
                });

        // --- Multi-phase bosses (Pattern A). ---
        AssetStoreRegistrar.registerStore(MultiPhaseBossAsset.class,
                new DefaultAssetMap<String, MultiPhaseBossAsset>(), "ZiggfreedCommon/Bosses",
                MultiPhaseBossAsset::getId, MultiPhaseBossAsset.CODEC, null);
        plugin.getEventRegistry().register(LoadedAssetsEvent.class, MultiPhaseBossAsset.class,
                (LoadedAssetsEvent<String, MultiPhaseBossAsset, DefaultAssetMap<String, MultiPhaseBossAsset>> ev) ->
                        MultiPhaseBossConfig.getInstance().mergePackLayer(AssetMergeAdapter.layer(ev.getAssetMap())));

        // --- Banded effects (Pattern A) - the codec face of EffectBand/EffectBandLadder. ---
        AssetStoreRegistrar.registerStore(BandedEffectAsset.class,
                new DefaultAssetMap<String, BandedEffectAsset>(), "ZiggfreedCommon/BandedEffects",
                BandedEffectAsset::getId, BandedEffectAsset.CODEC, null);
        plugin.getEventRegistry().register(LoadedAssetsEvent.class, BandedEffectAsset.class,
                (LoadedAssetsEvent<String, BandedEffectAsset, DefaultAssetMap<String, BandedEffectAsset>> ev) ->
                        BandedEffectConfig.getInstance().mergePackLayer(AssetMergeAdapter.layer(ev.getAssetMap())));

        // --- Encounter rules (Pattern A) - generic EncounterDirector config. ---
        AssetStoreRegistrar.registerStore(EncounterRuleAsset.class,
                new DefaultAssetMap<String, EncounterRuleAsset>(), "ZiggfreedCommon/EncounterRules",
                EncounterRuleAsset::getId, EncounterRuleAsset.CODEC, null);
        plugin.getEventRegistry().register(LoadedAssetsEvent.class, EncounterRuleAsset.class,
                (LoadedAssetsEvent<String, EncounterRuleAsset, DefaultAssetMap<String, EncounterRuleAsset>> ev) ->
                        EncounterRuleConfig.getInstance().mergePackLayer(AssetMergeAdapter.layer(ev.getAssetMap())));

        // --- Weighted prefab placements (Pattern A). ---
        AssetStoreRegistrar.registerStore(WeightedPrefabPlacementAsset.class,
                new DefaultAssetMap<String, WeightedPrefabPlacementAsset>(), "ZiggfreedCommon/PrefabPlacements",
                WeightedPrefabPlacementAsset::getId, WeightedPrefabPlacementAsset.CODEC, null);
        plugin.getEventRegistry().register(LoadedAssetsEvent.class, WeightedPrefabPlacementAsset.class,
                (LoadedAssetsEvent<String, WeightedPrefabPlacementAsset, DefaultAssetMap<String, WeightedPrefabPlacementAsset>> ev) ->
                        WeightedPrefabPlacementConfig.getInstance().mergePackLayer(AssetMergeAdapter.layer(ev.getAssetMap())));

        // --- Leaderboard layout (Pattern A). ---
        AssetStoreRegistrar.registerStore(LeaderboardLayoutAsset.class,
                new DefaultAssetMap<String, LeaderboardLayoutAsset>(), "ZiggfreedCommon/Leaderboard",
                LeaderboardLayoutAsset::getId, LeaderboardLayoutAsset.CODEC, null);
        plugin.getEventRegistry().register(LoadedAssetsEvent.class, LeaderboardLayoutAsset.class,
                (LoadedAssetsEvent<String, LeaderboardLayoutAsset, DefaultAssetMap<String, LeaderboardLayoutAsset>> ev) ->
                        LeaderboardLayoutConfig.getInstance().mergePackLayer(
                                AssetMergeAdapter.layer(ev.getAssetMap(), (id, a) -> a.toLayout(id))));

        // --- Arena definitions (Pattern A) - spatial layout (team spawns / objectives / pickups). ---
        AssetStoreRegistrar.registerStore(ArenaDefinitionAsset.class,
                new DefaultAssetMap<String, ArenaDefinitionAsset>(), "ZiggfreedCommon/Arenas",
                ArenaDefinitionAsset::getId, ArenaDefinitionAsset.CODEC, null);
        plugin.getEventRegistry().register(LoadedAssetsEvent.class, ArenaDefinitionAsset.class,
                (LoadedAssetsEvent<String, ArenaDefinitionAsset, DefaultAssetMap<String, ArenaDefinitionAsset>> ev) ->
                        ArenaDefinitionConfig.getInstance().mergePackLayer(AssetMergeAdapter.layer(ev.getAssetMap())));

        // --- Party settings (Pattern A). ---
        AssetStoreRegistrar.registerStore(PartySettingsAsset.class,
                new DefaultAssetMap<String, PartySettingsAsset>(), "ZiggfreedCommon/Party",
                PartySettingsAsset::getId, PartySettingsAsset.CODEC, null);
        plugin.getEventRegistry().register(LoadedAssetsEvent.class, PartySettingsAsset.class,
                (LoadedAssetsEvent<String, PartySettingsAsset, DefaultAssetMap<String, PartySettingsAsset>> ev) ->
                        PartySettingsConfig.getInstance().mergePackLayer(
                                AssetMergeAdapter.layer(ev.getAssetMap(), (id, a) -> a.toConfig())));

        // --- Dialogue option theme (Pattern A) - the data-driven look per option style kind. Common
        //     ships the neutral defaults as its own pack (DialogueOptionTheme/*.json); a consumer pack
        //     or owner overrides a kind by dropping the same-id file, and the DialogueOptionStyle enum
        //     stays only as the fail-closed fallback. ---
        AssetStoreRegistrar.registerStore(DialogueOptionThemeAsset.class,
                new DefaultAssetMap<String, DialogueOptionThemeAsset>(), "ZiggfreedCommon/DialogueOptionTheme",
                DialogueOptionThemeAsset::getId, DialogueOptionThemeAsset.CODEC, null);
        plugin.getEventRegistry().register(LoadedAssetsEvent.class, DialogueOptionThemeAsset.class,
                (LoadedAssetsEvent<String, DialogueOptionThemeAsset, DefaultAssetMap<String, DialogueOptionThemeAsset>> ev) ->
                        DialogueOptionThemeConfig.getInstance().mergePackLayer(
                                AssetMergeAdapter.layer(ev.getAssetMap(), (id, a) -> a.toTheme())));

        // --- World selectors (Pattern A) - the world-identity vocabulary. Common ships the
        //     structural Zc_Default / Zc_Any files; every other selector is consumer JSON or an
        //     owner entry in mods/ziggfreedcommon/world-selectors.json.
        //     The merge MUST invalidate WorldIdentity: the main world is added during
        //     universe boot, BEFORE this event folds the config, so its cached (and empty)
        //     name set would otherwise stand for the life of the process. WorldSelectorConfig
        //     invalidates from mergePackLayer itself; the explicit call keeps that visible at
        //     the site where forgetting it would break everything. ---
        AssetStoreRegistrar.registerStore(WorldSelectorAsset.class,
                new DefaultAssetMap<String, WorldSelectorAsset>(), "ZiggfreedCommon/WorldSelectors",
                WorldSelectorAsset::getId, WorldSelectorAsset.CODEC, null);
        plugin.getEventRegistry().register(LoadedAssetsEvent.class, WorldSelectorAsset.class,
                (LoadedAssetsEvent<String, WorldSelectorAsset, DefaultAssetMap<String, WorldSelectorAsset>> ev) -> {
                    WorldSelectorConfig.getInstance().mergePackLayer(
                            AssetMergeAdapter.layer(ev.getAssetMap(), (id, a) -> a.toDef(id)));
                    WorldIdentity.invalidateAll();
                });

        // --- NPC placements (Pattern A) - "put this NPC here, in these worlds, under these
        //     conditions". Common ships no placement content; every entry is consumer pack JSON.
        //     The merge clears the reconciler's per-world debounce (NpcPlacementConfig does it
        //     from mergePackLayer), so a reload takes effect on the next sweep instead of waiting
        //     for a world to be entered fresh. ---
        AssetStoreRegistrar.registerStore(NpcPlacementAsset.class,
                new DefaultAssetMap<String, NpcPlacementAsset>(), "ZiggfreedCommon/NpcPlacements",
                NpcPlacementAsset::getId, NpcPlacementAsset.CODEC, null);
        plugin.getEventRegistry().register(LoadedAssetsEvent.class, NpcPlacementAsset.class,
                (LoadedAssetsEvent<String, NpcPlacementAsset, DefaultAssetMap<String, NpcPlacementAsset>> ev) ->
                        NpcPlacementConfig.getInstance().mergePackLayer(
                                AssetMergeAdapter.layer(ev.getAssetMap())));

        // --- NPC identities (Pattern A) - the OVERLAY on top of the naming convention: aliases, one
        //     character across two roles, a rename. Most NPCs need no file here at all, because a
        //     character's id defaults to its role id in lower case and a placed NPC is already its
        //     placement's id. Merging drops the resolved identity index (NpcIdentityConfig does it
        //     from mergePackLayer), so a reload is visible on the next lookup. ---
        AssetStoreRegistrar.registerStore(NpcIdentityAsset.class,
                new DefaultAssetMap<String, NpcIdentityAsset>(), NpcIdentityAsset.TYPE_ROOT,
                NpcIdentityAsset::getId, NpcIdentityAsset.CODEC, null);
        plugin.getEventRegistry().register(LoadedAssetsEvent.class, NpcIdentityAsset.class,
                (LoadedAssetsEvent<String, NpcIdentityAsset, DefaultAssetMap<String, NpcIdentityAsset>> ev) ->
                        NpcIdentityConfig.getInstance().mergePackLayer(
                                AssetMergeAdapter.layer(ev.getAssetMap())));

        // --- Derived factors (Pattern A) - a factor id DEFINED as a formula over other factors,
        //     so a pack author adds a reading to the shared vocabulary with no Java. The asset id
        //     IS the factor id. No cache to invalidate: a registry that adopts a derived id keeps a
        //     provider that re-reads DerivedFactorConfig every call, so a re-import lands on the
        //     next resolve and a dropped file goes straight back to failing closed. ---
        AssetStoreRegistrar.registerStore(DerivedFactorAsset.class,
                new DefaultAssetMap<String, DerivedFactorAsset>(), "ZiggfreedCommon/Factors",
                DerivedFactorAsset::getId, DerivedFactorAsset.CODEC, null);
        plugin.getEventRegistry().register(LoadedAssetsEvent.class, DerivedFactorAsset.class,
                (LoadedAssetsEvent<String, DerivedFactorAsset, DefaultAssetMap<String, DerivedFactorAsset>> ev) ->
                        DerivedFactorConfig.getInstance().mergePackLayer(
                                AssetMergeAdapter.layer(ev.getAssetMap(),
                                        (id, a) -> a.getFormula() == null ? new FactorFormula() : a.getFormula())));

        // --- Quests (Pattern A) - one authored quest per file, with native Parent inheritance and a
        //     per-objective-id merge, so a child quest retunes one step and keeps its siblings.
        //     Common ships no quest CONTENT; every entry is consumer pack JSON, and each consumer
        //     folds the store into its own engine via QuestAssetStore.resolveAll(owner, ...). ---
        AssetStoreRegistrar.registerStore(QuestAsset.class,
                new DefaultAssetMap<String, QuestAsset>(), "ZiggfreedCommon/Quests",
                QuestAsset::getId, QuestAsset.CODEC, null);
        plugin.getEventRegistry().register(LoadedAssetsEvent.class, QuestAsset.class,
                (LoadedAssetsEvent<String, QuestAsset, DefaultAssetMap<String, QuestAsset>> ev) ->
                        QuestAssetStore.getInstance().mergeQuests(AssetMergeAdapter.layer(ev.getAssetMap())));

        // --- Quest generators (Pattern A) - "the same quest, once per ore" as one file. They are
        //     loaded AFTER the quests they inherit from, because expansion resolves each generated
        //     child against its Base out of the quest store. ---
        AssetStoreRegistrar.registerStore(QuestGeneratorAsset.class,
                new DefaultAssetMap<String, QuestGeneratorAsset>(), "ZiggfreedCommon/QuestGenerators",
                QuestGeneratorAsset::getId, QuestGeneratorAsset.CODEC, new Class<?>[]{QuestAsset.class});
        plugin.getEventRegistry().register(LoadedAssetsEvent.class, QuestGeneratorAsset.class,
                (LoadedAssetsEvent<String, QuestGeneratorAsset, DefaultAssetMap<String, QuestGeneratorAsset>> ev) ->
                        QuestAssetStore.getInstance().mergeGenerators(AssetMergeAdapter.layer(ev.getAssetMap())));

        // --- Achievements (Pattern A) - one authored achievement per file, with native Parent
        //     inheritance. Common ships no achievement CONTENT; every entry is consumer pack JSON,
        //     and each consumer folds the store into its own engine via
        //     AchievementAssetStore.resolveAll(owner). ---
        AssetStoreRegistrar.registerStore(AchievementAsset.class,
                new DefaultAssetMap<String, AchievementAsset>(), "ZiggfreedCommon/Achievements",
                AchievementAsset::getId, AchievementAsset.CODEC, null);
        plugin.getEventRegistry().register(LoadedAssetsEvent.class, AchievementAsset.class,
                (LoadedAssetsEvent<String, AchievementAsset, DefaultAssetMap<String, AchievementAsset>> ev) ->
                        AchievementAssetStore.getInstance().merge(AssetMergeAdapter.layer(ev.getAssetMap())));

        // --- Achievement categories (Pattern A) - the presentation half of the shared
        //     Listing.Category leaf: where a grouping label sits, what illustrates it, what it is
        //     called, and the order its subcategories read in. Every leaf is nullable, so a pack
        //     that only wants a different icon ships a file carrying nothing else. ---
        AssetStoreRegistrar.registerStore(AchievementCategoryAsset.class,
                new DefaultAssetMap<String, AchievementCategoryAsset>(), AchievementCategoryAsset.TYPE_ROOT,
                AchievementCategoryAsset::getId, AchievementCategoryAsset.CODEC, null);
        plugin.getEventRegistry().register(LoadedAssetsEvent.class, AchievementCategoryAsset.class,
                (LoadedAssetsEvent<String, AchievementCategoryAsset, DefaultAssetMap<String, AchievementCategoryAsset>> ev) ->
                        AchievementCategoryConfig.getInstance().mergePackLayer(
                                AssetMergeAdapter.layer(ev.getAssetMap())));

        // --- Achievement milestones (Pattern A) - the points ladder: a reward for reaching a
        //     running TOTAL rather than for any one achievement. The Threshold inside a file is its
        //     identity, so two files naming one number are one rung whatever they are called. ---
        AssetStoreRegistrar.registerStore(AchievementMilestoneAsset.class,
                new DefaultAssetMap<String, AchievementMilestoneAsset>(), AchievementMilestoneAsset.TYPE_ROOT,
                AchievementMilestoneAsset::getId, AchievementMilestoneAsset.CODEC, null);
        plugin.getEventRegistry().register(LoadedAssetsEvent.class, AchievementMilestoneAsset.class,
                (LoadedAssetsEvent<String, AchievementMilestoneAsset, DefaultAssetMap<String, AchievementMilestoneAsset>> ev) ->
                        AchievementMilestoneConfig.getInstance().mergePackLayer(
                                AssetMergeAdapter.layer(ev.getAssetMap())));

        try {
            CommonLog.LOGGER.atInfo().log(
                    "ZiggfreedCommon framework stores registered (Dialogues, Instances, "
                            + "Lootables, RollPools, RewardKinds, Bosses, BandedEffects, EncounterRules, PrefabPlacements, Leaderboard, "
                            + "Arenas, Party, WorldSelectors, NpcPlacements, NpcIdentities, Factors, "
                            + "Quests, QuestGenerators, Achievements, AchievementCategories, "
                            + "AchievementMilestones).");
        } catch (Throwable ignored) {
            // log-manager-less unit JVM: never let a presence log escape into setup().
        }
    }
}
