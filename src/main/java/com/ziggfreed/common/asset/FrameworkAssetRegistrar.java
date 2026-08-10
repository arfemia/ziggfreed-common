package com.ziggfreed.common.asset;

import javax.annotation.Nonnull;

import com.hypixel.hytale.assetstore.event.LoadedAssetsEvent;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.ziggfreed.common.ZiggfreedCommonPlugin;
import com.ziggfreed.common.dialogue.DialogueOptionThemeConfig;
import com.ziggfreed.common.dialogue.asset.DialogueAssetStore;
import com.ziggfreed.common.dialogue.asset.DialogueOptionThemeAsset;
import com.ziggfreed.common.dialogue.asset.ZcDialogueAsset;
import com.ziggfreed.common.dialogue.asset.ZcDialogueTemplateAsset;
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
import com.ziggfreed.common.instance.reward.LootTableAsset;
import com.ziggfreed.common.instance.reward.LootTableConfig;
import com.ziggfreed.common.npc.placement.NpcPlacementAsset;
import com.ziggfreed.common.npc.placement.NpcPlacementConfig;
import com.ziggfreed.common.party.PartySettingsAsset;
import com.ziggfreed.common.party.PartySettingsConfig;
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
 * {@code primary} and {@code any} names, which are the vocabulary every other selector-aware
 * file is written against - a consumer would have to re-declare them in every pack
 * otherwise). Both ride the jar's own asset pack, so an owner overrides either by dropping a
 * same-id file. The only ordering edge is {@code Dialogues loadsAfter DialogueTemplates} so
 * a native {@code Parent} base is in the resolution pool at decode time.
 *
 * <p>The dialogue store is Pattern B (raw-Payload): common folds the raw bodies +
 * templates here, but the DECODE is per-consumer-engine, so a consumer calls
 * {@link DialogueAssetStore#resolveAll} with its own built {@code DialogueEngine} after
 * load. Every other store is Pattern A (a structured codec is the schema authority).
 */
public final class FrameworkAssetRegistrar {

    private FrameworkAssetRegistrar() {
    }

    /** Register every framework store + its merge listener. Call once from {@code setup()}. */
    public static void registerAll(@Nonnull JavaPlugin plugin) {
        // --- Dialogue templates (Pattern B) - registered FIRST so Dialogues can loadsAfter it. ---
        AssetStoreRegistrar.registerStore(ZcDialogueTemplateAsset.class,
                new DefaultAssetMap<String, ZcDialogueTemplateAsset>(), "ZiggfreedCommon/DialogueTemplates",
                ZcDialogueTemplateAsset::getId, ZcDialogueTemplateAsset.CODEC, null);
        plugin.getEventRegistry().register(LoadedAssetsEvent.class, ZcDialogueTemplateAsset.class,
                (LoadedAssetsEvent<String, ZcDialogueTemplateAsset, DefaultAssetMap<String, ZcDialogueTemplateAsset>> ev) ->
                        DialogueAssetStore.getInstance().mergeTemplates(
                                AssetMergeAdapter.layer(ev.getAssetMap(), (id, a) -> a.getPayloadAsJsonObject())));

        // --- Dialogues (Pattern B) - loadsAfter the templates store. ---
        AssetStoreRegistrar.registerStore(ZcDialogueAsset.class,
                new DefaultAssetMap<String, ZcDialogueAsset>(), "ZiggfreedCommon/Dialogues",
                ZcDialogueAsset::getId, ZcDialogueAsset.CODEC,
                new Class<?>[]{ZcDialogueTemplateAsset.class});
        plugin.getEventRegistry().register(LoadedAssetsEvent.class, ZcDialogueAsset.class,
                (LoadedAssetsEvent<String, ZcDialogueAsset, DefaultAssetMap<String, ZcDialogueAsset>> ev) ->
                        DialogueAssetStore.getInstance().mergeBodies(
                                AssetMergeAdapter.layer(ev.getAssetMap(), (id, a) -> a)));

        // --- Instance presets (the cross-cutting preset layer, relocated to common-owned). ---
        AssetStoreRegistrar.registerStore(InstancePresetAsset.class,
                new DefaultAssetMap<String, InstancePresetAsset>(), "ZiggfreedCommon/Instances",
                InstancePresetAsset::getId, InstancePresetAsset.CODEC, null);
        plugin.getEventRegistry().register(LoadedAssetsEvent.class, InstancePresetAsset.class,
                (LoadedAssetsEvent<String, InstancePresetAsset, DefaultAssetMap<String, InstancePresetAsset>> ev) ->
                        InstancePresetConfig.getInstance().mergePackLayer(
                                AssetMergeAdapter.layer(ev.getAssetMap(), (id, a) -> a.toPreset(id))));

        // --- Loot tables (Pattern A) - score-tiered reward pools a preset references by id. ---
        AssetStoreRegistrar.registerStore(LootTableAsset.class,
                new DefaultAssetMap<String, LootTableAsset>(), "ZiggfreedCommon/LootTables",
                LootTableAsset::getId, LootTableAsset.CODEC, null);
        plugin.getEventRegistry().register(LoadedAssetsEvent.class, LootTableAsset.class,
                (LoadedAssetsEvent<String, LootTableAsset, DefaultAssetMap<String, LootTableAsset>> ev) ->
                        LootTableConfig.getInstance().mergePackLayer(
                                AssetMergeAdapter.layer(ev.getAssetMap(), (id, a) -> a.toLootTable())));

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
        //     structural Zc_Primary / Zc_Any files; every other selector is consumer JSON.
        //     The merge MUST invalidate WorldIdentity: the primary world is added during
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

        try {
            ZiggfreedCommonPlugin.LOGGER.atInfo().log(
                    "ZiggfreedCommon framework stores registered (Dialogues, DialogueTemplates, Instances, "
                            + "LootTables, Bosses, BandedEffects, EncounterRules, PrefabPlacements, Leaderboard, "
                            + "Arenas, Party, WorldSelectors, NpcPlacements).");
        } catch (Throwable ignored) {
            // log-manager-less unit JVM: never let a presence log escape into setup().
        }
    }
}
