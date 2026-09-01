package com.ziggfreed.common.asset;

import javax.annotation.Nonnull;

import com.hypixel.hytale.assetstore.event.LoadedAssetsEvent;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.ziggfreed.common.CommonLog;
import com.ziggfreed.common.ZiggfreedCommonPlugin;
import com.ziggfreed.common.dialogue.schema.DialogueFragmentConfig;
import com.ziggfreed.common.dialogue.style.DialogueOptionThemeConfig;
import com.ziggfreed.common.dialogue.asset.DialogueAssetStore;
import com.ziggfreed.common.dialogue.asset.DialogueFragmentAsset;
import com.ziggfreed.common.dialogue.asset.DialogueOptionThemeAsset;
import com.ziggfreed.common.dialogue.asset.ZcDialogueAsset;
import com.ziggfreed.common.factor.DerivedFactorAsset;
import com.ziggfreed.common.factor.DerivedFactorConfig;
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
import com.ziggfreed.common.loot.stamp.StatDisplayAsset;
import com.ziggfreed.common.loot.stamp.StatDisplayConfig;
import com.ziggfreed.common.npc.NpcIdentityAsset;
import com.ziggfreed.common.npc.NpcIdentityConfig;
import com.ziggfreed.common.npc.placement.asset.NpcPlacementAsset;
import com.ziggfreed.common.npc.placement.asset.NpcPlacementConfig;
import com.ziggfreed.common.npc.placement.asset.NpcPlacementOverrides;
import com.ziggfreed.common.party.PartySettingsAsset;
import com.ziggfreed.common.party.PartySettingsConfig;
import com.ziggfreed.common.board.asset.BoardAsset;
import com.ziggfreed.common.board.asset.BoardAssetStore;
import com.ziggfreed.common.board.asset.BoardConfig;
import com.ziggfreed.common.board.asset.BountyAsset;
import com.ziggfreed.common.commerce.fold.CommerceCatalogs;
import com.ziggfreed.common.commerce.fold.CommerceOwnerLayers;
import com.ziggfreed.common.currency.asset.CurrencyAsset;
import com.ziggfreed.common.currency.asset.CurrencyConfig;
import com.ziggfreed.common.shop.asset.StorefrontAsset;
import com.ziggfreed.common.shop.asset.ShopAssetStore;
import com.ziggfreed.common.shop.asset.ShopConfig;
import com.ziggfreed.common.shop.asset.ShopEntryAsset;
import com.ziggfreed.common.shop.asset.ShopEntryGeneratorAsset;
import com.ziggfreed.common.shop.asset.ShopPoolAsset;
import com.ziggfreed.common.shop.asset.ShopPoolConfig;
import com.ziggfreed.common.achievement.asset.AchievementAsset;
import com.ziggfreed.common.achievement.asset.AchievementAssetStore;
import com.ziggfreed.common.achievement.asset.AchievementCategoryAsset;
import com.ziggfreed.common.achievement.asset.AchievementCategoryConfig;
import com.ziggfreed.common.achievement.asset.AchievementMilestoneAsset;
import com.ziggfreed.common.achievement.asset.AchievementMilestoneConfig;
import com.ziggfreed.common.feedback.moment.FeedbackMomentAsset;
import com.ziggfreed.common.feedback.moment.FeedbackMomentConfig;
import com.ziggfreed.common.quest.asset.QuestAsset;
import com.ziggfreed.common.quest.asset.QuestAssetStore;
import com.ziggfreed.common.quest.asset.QuestGeneratorAsset;
import com.ziggfreed.common.world.WeightedPrefabPlacementAsset;
import com.ziggfreed.common.world.WeightedPrefabPlacementConfig;

/**
 * The ONE registrar for ziggfreed-common's framework asset stores, called once from
 * {@link ZiggfreedCommonPlugin#setup()}. Common OWNS these stores: it registers each
 * asset class exactly once at {@code Server/ZiggfreedCommon/<Type>/} and wires the
 * single {@code LoadedAssetsEvent} merge listener that folds the loaded entries into the
 * common config singletons. A consumer mod (Kweebec, a future dungeon) authors JSON into
 * those paths and READS the resolved config back; it must NOT re-register these classes
 * (Hytale's {@code AssetRegistry} keys stores by class and throws on a duplicate).
 *
 * <p>Common ships neutral DEFAULT content for these stores where a bare server would otherwise
 * get nothing, and a consumer overrides it BY ID: there is no add/replace pack-control gate, a
 * later pack's same-id file simply wins (last-pack-wins by id, and packs load in manifest
 * dependency order, so a consumer that lists this library as a dependency loads after it). The
 * first such store is {@code FeedbackMoments}: one file per moment the library's own engines
 * announce, so a quest completing on a bare server still draws a notice, and a consumer's
 * {@code Quest_Completed.json} replaces the library's outright. {@code DialogueOptionTheme} is
 * the older instance of the same rule (the neutral look per option kind, so a page renders
 * before anyone authors a theme). Both ride the jar's own asset pack, so an owner overrides
 * either by dropping a same-id file. Everything else in these stores is consumer pack JSON.
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
        // --- Dialogue fragments (Pattern A) - one group of repeated lines per file, named by any
        //     screen with IncludeOptions, so a farewell or an open-the-menu row is written once for
        //     the whole server. Common ships no fragment CONTENT; every entry is consumer pack JSON.
        //     They load BEFORE Dialogues because a conversation splices the groups it names as it is
        //     read, and a group that is not there yet would silently drop its lines. ---
        AssetStoreRegistrar.registerStore(DialogueFragmentAsset.class,
                new DefaultAssetMap<String, DialogueFragmentAsset>(), DialogueFragmentAsset.TYPE_ROOT,
                DialogueFragmentAsset::getId, DialogueFragmentAsset.CODEC, null);
        plugin.getEventRegistry().register(LoadedAssetsEvent.class, DialogueFragmentAsset.class,
                (LoadedAssetsEvent<String, DialogueFragmentAsset, DefaultAssetMap<String, DialogueFragmentAsset>> ev) ->
                        DialogueFragmentConfig.getInstance().mergePackLayer(
                                AssetMergeAdapter.layer(ev.getAssetMap(), (id, a) -> a.getOptions())));

        // --- Dialogues (Pattern A) - one authored conversation per file, with native Parent
        //     inheritance and a per-screen merge, so a child conversation restates one screen and
        //     keeps the rest. Common ships no dialogue CONTENT; every entry is consumer pack JSON,
        //     and every consumer reads the whole folder back via DialogueAssetStore.dialogues(). ---
        AssetStoreRegistrar.registerStore(ZcDialogueAsset.class,
                new DefaultAssetMap<String, ZcDialogueAsset>(), "ZiggfreedCommon/Dialogues",
                ZcDialogueAsset::getId, ZcDialogueAsset.CODEC,
                new Class<?>[]{DialogueFragmentAsset.class});
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

        // --- Stat displays (Pattern A) - what ONE stat is CALLED on a stamped item. Most stats need
        //     no file: the default naming reads the client's own itemTooltip label first, so a stat
        //     the game (or a mod) already names is named correctly with nothing authored. A file is
        //     for wording or colouring a stat that would otherwise read badly, and it outranks
        //     whatever a mod registered in code, so a server can correct any stat's wording. ---
        AssetStoreRegistrar.registerStore(StatDisplayAsset.class,
                new DefaultAssetMap<String, StatDisplayAsset>(), StatDisplayAsset.TYPE_ROOT,
                StatDisplayAsset::getId, StatDisplayAsset.CODEC, null);
        plugin.getEventRegistry().register(LoadedAssetsEvent.class, StatDisplayAsset.class,
                (LoadedAssetsEvent<String, StatDisplayAsset, DefaultAssetMap<String, StatDisplayAsset>> ev) ->
                        StatDisplayConfig.getInstance().mergePackLayer(AssetMergeAdapter.layer(ev.getAssetMap())));

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

        // --- NPC placements (Pattern A) - "put this NPC here, in these worlds, under these
        //     conditions". Common ships no placement content; every entry is consumer pack JSON.
        //     The merge clears the reconciler's per-world debounce (NpcPlacementConfig does it
        //     from mergePackLayer), so a reload takes effect on the next sweep instead of waiting
        //     for a world to be entered fresh. ---
        AssetStoreRegistrar.registerStore(NpcPlacementAsset.class,
                new DefaultAssetMap<String, NpcPlacementAsset>(), "ZiggfreedCommon/NpcPlacements",
                NpcPlacementAsset::getId, NpcPlacementAsset.CODEC, null);
        plugin.getEventRegistry().register(LoadedAssetsEvent.class, NpcPlacementAsset.class,
                (LoadedAssetsEvent<String, NpcPlacementAsset, DefaultAssetMap<String, NpcPlacementAsset>> ev) -> {
                    NpcPlacementConfig.getInstance().mergePackLayer(
                            AssetMergeAdapter.layer(ev.getAssetMap()));
                    // The owner file's own placements land HERE rather than at setup, for the same
                    // reason the commerce owner layers do: an owner entry is decoded against
                    // whatever the packs already say about that id, so it has nothing to inherit
                    // from until the pack layer has landed.
                    NpcPlacementOverrides.getInstance().applyOwnerLayer();
                });

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

        // --- Derived factors (Pattern A) - a factor id DEFINED as a formula over other factors
        //     and/or NAMED for every surface that explains a requirement on it, with no Java. A
        //     defining file's id IS the factor id; a naming overlay targets one through its Factor
        //     leaf. No cache to invalidate: a registry that adopts a derived id keeps a provider
        //     that re-reads DerivedFactorConfig every call, and FactorNames walks the fold per
        //     question, so a re-import lands on the next resolve and a dropped file goes straight
        //     back to failing closed. ---
        AssetStoreRegistrar.registerStore(DerivedFactorAsset.class,
                new DefaultAssetMap<String, DerivedFactorAsset>(), "ZiggfreedCommon/Factors",
                DerivedFactorAsset::getId, DerivedFactorAsset.CODEC, null);
        plugin.getEventRegistry().register(LoadedAssetsEvent.class, DerivedFactorAsset.class,
                (LoadedAssetsEvent<String, DerivedFactorAsset, DefaultAssetMap<String, DerivedFactorAsset>> ev) ->
                        DerivedFactorConfig.getInstance().mergePackLayer(
                                AssetMergeAdapter.layer(ev.getAssetMap())));

        // --- Feedback moments (Pattern A) - what this server DOES when one lifecycle moment
        //     happens: a toast, a server banner, a jingle, a command. The asset id IS the moment id
        //     (Quest_Completed, Achievement_Unlocked, ...). The library ships a neutral default file
        //     for each moment its own engines announce (zc-presentation's resources); a consumer's
        //     same-id file replaces it by pack order, and a moment nobody authored does nothing. No
        //     cache to invalidate: the engine resolves through the config on every moment, so a
        //     re-import lands on the next one. ---
        AssetStoreRegistrar.registerStore(FeedbackMomentAsset.class,
                new DefaultAssetMap<String, FeedbackMomentAsset>(), FeedbackMomentAsset.TYPE_ROOT,
                FeedbackMomentAsset::getId, FeedbackMomentAsset.CODEC, null);
        plugin.getEventRegistry().register(LoadedAssetsEvent.class, FeedbackMomentAsset.class,
                (LoadedAssetsEvent<String, FeedbackMomentAsset, DefaultAssetMap<String, FeedbackMomentAsset>> ev) ->
                        FeedbackMomentConfig.getInstance().mergePackLayer(
                                AssetMergeAdapter.layer(ev.getAssetMap())));

        // --- Quests (Pattern A) - one authored quest per file, with native Parent inheritance and a
        //     per-objective-id merge, so a child quest retunes one step and keeps its siblings.
        //     Common ships no quest CONTENT; every entry is consumer pack JSON, and each consumer
        //     folds the whole store into its own engine via QuestAssetStore.resolveAll(...). ---
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
        //     and every consumer folds the whole store into its own engine via
        //     AchievementAssetStore.resolveAll(). ---
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

        // --- Currencies (Pattern A) - one wallet per file: what backs it, what it is worth at most,
        //     and how it wears away. The merge also re-reads the server owner's own
        //     mods/ziggfreedcommon/currencies.json, which is why it runs HERE rather than at setup:
        //     an owner entry is decoded against whatever the packs already say about that id, so it
        //     has nothing to inherit from until the pack layer has landed. ---
        AssetStoreRegistrar.registerStore(CurrencyAsset.class,
                new DefaultAssetMap<String, CurrencyAsset>(), CurrencyAsset.TYPE_ROOT,
                CurrencyAsset::getId, CurrencyAsset.CODEC, null);
        plugin.getEventRegistry().register(LoadedAssetsEvent.class, CurrencyAsset.class,
                (LoadedAssetsEvent<String, CurrencyAsset, DefaultAssetMap<String, CurrencyAsset>> ev) -> {
                    CurrencyConfig.getInstance().mergePackLayer(AssetMergeAdapter.layer(ev.getAssetMap()));
                    CommerceOwnerLayers.reloadCurrencies();
                });

        // --- Shops (Pattern A) - the storefront PAGE: what it is called, which wallets its header
        //     shows, the order its shelves read in. What is for sale is a file per offer naming it,
        //     so adding one thing to a shop never means editing the shop. Owner layer
        //     mods/ziggfreedcommon/shops.json, re-read on the same event and for the same reason. ---
        AssetStoreRegistrar.registerStore(StorefrontAsset.class,
                new DefaultAssetMap<String, StorefrontAsset>(), StorefrontAsset.TYPE_ROOT,
                StorefrontAsset::getId, StorefrontAsset.CODEC, null);
        plugin.getEventRegistry().register(LoadedAssetsEvent.class, StorefrontAsset.class,
                (LoadedAssetsEvent<String, StorefrontAsset, DefaultAssetMap<String, StorefrontAsset>> ev) -> {
                    ShopConfig.getInstance().mergePackLayer(AssetMergeAdapter.layer(ev.getAssetMap()));
                    CommerceOwnerLayers.reloadShops();
                });

        // --- ShopPools (Pattern A) - one rotating shelf inside a storefront: its cadence, how it
        //     draws, the shape of one rotation, and what a reroll costs. ---
        AssetStoreRegistrar.registerStore(ShopPoolAsset.class,
                new DefaultAssetMap<String, ShopPoolAsset>(), ShopPoolAsset.TYPE_ROOT,
                ShopPoolAsset::getId, ShopPoolAsset.CODEC, null);
        plugin.getEventRegistry().register(LoadedAssetsEvent.class, ShopPoolAsset.class,
                (LoadedAssetsEvent<String, ShopPoolAsset, DefaultAssetMap<String, ShopPoolAsset>> ev) -> {
                    ShopPoolConfig.getInstance().mergePackLayer(AssetMergeAdapter.layer(ev.getAssetMap()));
                    CommerceOwnerLayers.reloadShopPools();
                });

        // --- ShopEntries (Pattern A) - one offer per file: a price in exchange for a reward, both in
        //     the library's shared vocabularies. Folding is part of the SAME listener for the reason
        //     the reward kinds are: the catalogue the purchase engine reads is built FROM the store,
        //     and a registrar that merged without rebuilding it would leave every shop showing what
        //     the last load said. ---
        AssetStoreRegistrar.registerStore(ShopEntryAsset.class,
                new DefaultAssetMap<String, ShopEntryAsset>(), ShopEntryAsset.TYPE_ROOT,
                ShopEntryAsset::getId, ShopEntryAsset.CODEC, null);
        plugin.getEventRegistry().register(LoadedAssetsEvent.class, ShopEntryAsset.class,
                (LoadedAssetsEvent<String, ShopEntryAsset, DefaultAssetMap<String, ShopEntryAsset>> ev) -> {
                    ShopAssetStore.getInstance().mergeEntries(AssetMergeAdapter.layer(ev.getAssetMap()));
                    CommerceCatalogs.refreshShops();
                });

        // --- ShopEntryGenerators (Pattern A) - "the same packet, once per skill" as one file. They
        //     are loaded AFTER the offers they inherit from, because expansion resolves each
        //     generated child against its Base out of the offer store. ---
        AssetStoreRegistrar.registerStore(ShopEntryGeneratorAsset.class,
                new DefaultAssetMap<String, ShopEntryGeneratorAsset>(), ShopEntryGeneratorAsset.TYPE_ROOT,
                ShopEntryGeneratorAsset::getId, ShopEntryGeneratorAsset.CODEC,
                new Class<?>[]{ShopEntryAsset.class});
        plugin.getEventRegistry().register(LoadedAssetsEvent.class, ShopEntryGeneratorAsset.class,
                (LoadedAssetsEvent<String, ShopEntryGeneratorAsset, DefaultAssetMap<String, ShopEntryGeneratorAsset>> ev) -> {
                    ShopAssetStore.getInstance().mergeGenerators(AssetMergeAdapter.layer(ev.getAssetMap()));
                    CommerceCatalogs.refreshShops();
                });

        // --- Boards (Pattern A) - the NOTICE a rotating set of contracts is posted on: how often the
        //     postings change, what shape one takes, and what a player has to be before they may take
        //     the heavier work. Owner layer mods/ziggfreedcommon/boards.json. ---
        AssetStoreRegistrar.registerStore(BoardAsset.class,
                new DefaultAssetMap<String, BoardAsset>(), BoardAsset.TYPE_ROOT,
                BoardAsset::getId, BoardAsset.CODEC, null);
        plugin.getEventRegistry().register(LoadedAssetsEvent.class, BoardAsset.class,
                (LoadedAssetsEvent<String, BoardAsset, DefaultAssetMap<String, BoardAsset>> ev) -> {
                    BoardConfig.getInstance().mergePackLayer(AssetMergeAdapter.layer(ev.getAssetMap()));
                    CommerceOwnerLayers.reloadBoards();
                });

        // --- Bounties (Pattern A) - one contract per file, reusing the quest schema's own groups,
        //     plus the boards it may be posted on. Publishing to the shared quest runtime is part of
        //     the SAME listener for the reason the shop fold is: a bounty IS a quest only once the
        //     runtime has heard of it, and a board can draw a contract it cannot accept until then.
        //     Published as this library's own layer, so a consumer's outranks it. ---
        AssetStoreRegistrar.registerStore(BountyAsset.class,
                new DefaultAssetMap<String, BountyAsset>(), BountyAsset.TYPE_ROOT,
                BountyAsset::getId, BountyAsset.CODEC, null);
        plugin.getEventRegistry().register(LoadedAssetsEvent.class, BountyAsset.class,
                (LoadedAssetsEvent<String, BountyAsset, DefaultAssetMap<String, BountyAsset>> ev) -> {
                    BoardAssetStore.getInstance().merge(AssetMergeAdapter.layer(ev.getAssetMap()));
                    CommerceCatalogs.publishBounties();
                });

        try {
            CommonLog.LOGGER.atInfo().log(
                    "ZiggfreedCommon framework stores registered (DialogueFragments, Dialogues, Instances, "
                            + "Lootables, RollPools, StatDisplays, RewardKinds, Bosses, BandedEffects, EncounterRules, PrefabPlacements, Leaderboard, "
                            + "Arenas, Party, NpcPlacements, NpcIdentities, Factors, FeedbackMoments, "
                            + "Quests, QuestGenerators, Achievements, AchievementCategories, "
                            + "AchievementMilestones, Currencies, Shops, ShopPools, ShopEntries, "
                            + "ShopEntryGenerators, Boards, Bounties).");
        } catch (Throwable ignored) {
            // log-manager-less unit JVM: never let a presence log escape into setup().
        }
    }
}
