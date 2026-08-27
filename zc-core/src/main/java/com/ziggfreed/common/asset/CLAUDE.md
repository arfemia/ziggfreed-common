# asset/ - the framework asset-store backbone

Router for `com.ziggfreed.common.asset`. The generic, mod-agnostic plumbing every framework codec asset shares, PLUS the one registrar that wires them. **Common OWNS its framework stores**: a store is keyed by asset Java class in a process-static registry and may be registered ONCE - so common (not a consumer) registers each framework class exactly once, at `Server/ZiggfreedCommon/<Type>/`, and owns the single `LoadedAssetsEvent` merge listener. A consumer authors JSON into those paths and READS the resolved config singleton back; it must NOT re-register a framework class (the second `register` throws at load).

- **[`AssetStoreRegistrar`](AssetStoreRegistrar.java)** - `registerStore(class, map, path, keyFn, codec, loadsAfter)`: registers ONE Pattern-A store, hiding the package-protected `AssetStore.Builder` cast chain. Mod-agnostic (the path is a caller arg).
- **[`AssetMergeAdapter`](AssetMergeAdapter.java)** - `layer(assetMap[, (id,asset)->mapped])`: folds a `LoadedAssetsEvent` map into an `id -> value` layer, skipping the engine-base (`DEFAULT_PACK_KEY`) entries and lower-casing ids. The generic of the per-type hand-rolled fold.
- **[`AbstractKeyedAssetConfig<T>`](AbstractKeyedAssetConfig.java)** - the generic `defaults < pack < owner` fold base (synchronized writes, concurrent reads, lower-cased ids, idempotent re-import). Every framework config singleton extends it (`InstancePresetConfig`, `MultiPhaseBossConfig`, `BandedEffectConfig`, `EncounterRuleConfig`, `WeightedPrefabPlacementConfig`, `LeaderboardLayoutConfig`, `PartySettingsConfig`, `ArenaDefinitionConfig`, `LootableConfig`); the singleton adds only its `T` binding + `getInstance()` + type getters. Common ships no jar CONTENT (`loadDefaults` is optional; content is consumer pack JSON) - the one exception is STRUCTURAL, not content: `DialogueOptionTheme/*.json`. A config may also OVERRIDE the merge methods to rebuild a derived view - `LootableConfig` does, for the contribution fold.
- **[`EditorDataSets`](EditorDataSets.java)** - serves the value lists behind the `UIEditor.Dropdown`
  dataset ids this library's codecs declare, so the in-game Asset Editor offers a pick list instead
  of a free-text box. `live(registry, dataSetId, supplier)` answers off a runtime registry at request
  time (a late registration simply widens the next answer); `fixed(registry, dataSetId, values...)`
  serves a closed compile-time set. Both are try-guarded end to end - **a server without the Asset
  Editor module must degrade to free text, never fail startup** - and both must stay cheap and
  side-effect free, since the request is an async event. Holds the two factor dataset ids
  (`ziggfreedcommon:placement_factors`, `ziggfreedcommon:factors`); the composition that answers them
  lives in `ZiggfreedCommonPlugin`, the only place that can see both the placement facade and the
  Factors assets. **A dropdown is authoring convenience, never validation** - hand-written JSON never
  passes through the editor, so every dataset keeps its validator check.
- **[`EditorSchema`](EditorSchema.java)** - schema-only Asset Editor hints attached to a codec field
  with `.metadata(...)`: `defaultValue(...)` declares a nullable leaf's effective unauthored value
  (an `Enabled` meaning true, a `Weight` meaning 1) so the editor shows the real default instead of
  the control's zero-state, and `oneOf(...)`/`oneOfDocumented(...)` declare a CLOSED string
  vocabulary as the engine's enum shape (`enum` + `hytale.type: Enum` + per-value descriptions) so
  the editor offers a dropdown; a string-array leaf gets the set on its items. Decode is never
  touched (null still means inherit-then-default at the read site), and **enum belongs only on a
  vocabulary closed BY CODE** - a pack-extensible id set stays a plain string, because a dropdown
  that rejects a legal pack value is worse than a text box. Consumed across zc, the MMO jar,
  rpg-stations, mob-scaling and kweebec.
- **[`NestedAssetId`](NestedAssetId.java)** - lets an author group asset files into folders AND have
  the folder name become part of the id. The engine keys an asset by its FILENAME alone
  (`AssetStore.decodeFilePathKey`), so `Zones/Wilds/Trork_Trouble.json` and
  `Zones/Ashlands/Trork_Trouble.json` are ONE id and the second silently replaces the first. Marking
  a folder `_Wilds` makes it contribute: `effectiveId(path, typeRoot, filenameId)` folds every
  `_`-marked ancestor (marker stripped, lower-cased, stacked in path order, joined with `_`) onto
  the front of the filename id. An UNMARKED folder contributes nothing, so an existing tree keeps
  the ids it has and nothing moves under an author who did not opt in. `typeRoot` bounds the scan to
  directories below the store's content path, which is what keeps a checkout at `D:\_work\...` from
  putting `work` on the front of every id. **Wired into QUESTS only** (`quest/asset/QuestAsset`'s
  `afterDecode`, off `AssetExtraInfo.getAssetPath()`); every other type keys plainly off its
  filename. Renaming a marked folder renames every id beneath it, and an id is what saved progress
  is filed under.
- **[`AbstractRawJsonAsset`](AbstractRawJsonAsset.java)** - the raw-Payload base (`Name`+`Payload` BSON, `rawCodec(...)`, `getPayloadAsJsonObject()`). Use ONLY for a type whose raw body must survive a pre-pass, which today means an `extends`/`params` template DSL. (Dialogues used to be the other case and are now Pattern A: their body decodes through a codec graph assembled from the registered vocabulary, behind a deferred codec.) A self-contained type is a structured codec instead. Lifted from the MMO's own copy; carries `org.bson` (available via the Hytale jar's `Codec.BSON_DOCUMENT`).
- **[`FrameworkAssetRegistrar`](../../../../../../../../src/main/java/com/ziggfreed/common/asset/FrameworkAssetRegistrar.java)** - `registerAll(plugin)`, called once from `ZiggfreedCommonPlugin.setup()`. Registers the framework stores + their merge listeners (Dialogues, Instances, **Lootables** (`loot/LootableAsset` -> `loot/LootableConfig`, the named conditional loot tables anything references by id; its config OVERRIDES `resolve` so a table's `ContributesTo` enrichers are already folded in for every reader), Bosses, BandedEffects, EncounterRules, PrefabPlacements, Leaderboard, Arenas, Party, **NpcPlacements** (`npc/placement/NpcPlacementAsset` -> `NpcPlacementConfig`, the NPC placement engine's content; its merge clears the reconciler's per-world sweep debounce + the resolved-position cache, so a reload lands on the next sweep instead of waiting for a world to be entered fresh), **Factors** (`factor/DerivedFactorAsset` -> `factor/DerivedFactorConfig`, a factor id DEFINED as a formula over other factors, the file name being the id; nothing to invalidate - a registry that adopts a derived id re-reads the config every call), **Quests** + **QuestGenerators** (`quest/asset/QuestAsset` + `QuestGeneratorAsset` -> `quest/asset/QuestAssetStore`, one authored quest per file plus the files that write a whole family; the fold into a `QuestPool` is deferred to a consumer's `resolveAll(enumerators)` because both the value sources and the engine are per-consumer), **Achievements** (`achievement/asset/AchievementAsset` -> `AchievementAssetStore`, folded the same deferred way), **AchievementCategories** + **AchievementMilestones** (`achievement/asset/AchievementCategoryAsset` + `AchievementMilestoneAsset` -> their own `AbstractKeyedAssetConfig` singletons: the display taxonomy behind the shared `Listing.Category` leaf, and the points ladder `zc-objectives`' `ProgressionDefaults` publishes into the runtime)). The one ordering edge is QuestGenerators `loadsAfter` Quests (a generated child is decoded against its `Base` out of the quest store); it is pinned by `FrameworkStoreOrderTest`. No PackControl gate (common has no defaults to add/replace). To add a framework type: write the asset (PascalCase codec keys, no `Id`/`Parent`/`Tags`) + a config extending `AbstractKeyedAssetConfig`, register it here, and add its `CODEC` to `AssetCodecInitTest` (the static-init / PascalCase guard).
