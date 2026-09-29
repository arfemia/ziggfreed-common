# CLAUDE.md - zc-entity

Puppets, performers, per-player flair, the item-carried stat bridge with the gear-set engine on top
of it, the one item reader and the native recipe index: the entity-presentation, entity-stat and
item primitives that need real engine entity/item data, split out from the domain-free `factor/` and
`stats/` cores that live in `zc-core`.

## Build

Part of the fourteen-module `ziggfreed-common` build (`gradle/zc-module.gradle` convention, Java 25,
compiles as `:zc-entity`). See the root [`CLAUDE.md`](../CLAUDE.md) for the aggregate build.

## Dependencies

- **Depends on**: `zc-core` only. The world-eviction seam a performer's per-world bookkeeping
  registers against is the `cast.WorldEvictors` zc-core primitive, which is why this module rests
  on core alone rather than needing an edge to `zc-cast`.
- **Depended on by**: `zc-objectives`, `zc-world`, `zc-encounter` (the boss framework folds this
  module's portable `hytale:` factor library into its own registry; it reads nothing out of
  `entity/`).
- **Reverse-edge trap**: none declared today. This module carries no domain vocabulary of its own
  (it is entity-presentation + item-stat plumbing), so an edge upward to a domain module (loot,
  progression, dialogue) would be the first sign something domain-specific had leaked in here.

## Packages

- [`entity/`](src/main/java/com/ziggfreed/common/entity/CLAUDE.md) - `PlayerModelService`,
  `PlayerPuppetService` (clone a live `PlayerSkin` onto a networked spawned entity, held-item
  mirroring, the `Scale` self-hide/reveal pair), `ItemPropEntityService`, `PuppetNav` (bounded A*
  over `CollisionModule`), `HeldItemUtil` (a held tool's gather-power SPREAD selection, e.g. picking
  the right power out of a dozen authored on one item), `ItemReadings` (the ONE reader of what an
  item is worth - quality (the index a stack carries, or an item's current one), item level, wear,
  the item's own additive `StatModifiers` - that every item-shaped factor reads through, plus the
  metadata keys a stack carries and which of them no mod declared safe to destroy with it, over
  zc-core's `inventory/DisposableItemMetadata`), `PlayerIdentityCache` (resolves a player's
  UUID on the world thread, so an off-thread engine callback holding a bare `Player` never needs the
  deprecated-for-removal `Entity.getUuid()`), and `EntityBootstrap` (this module's own `setup()`
  registration phases: `installEquipStatBridge` (the ONE `EquipStatBridge`, its three trigger
  systems and the `equipStatBridge()` accessor a consumer reads it back through) /
  `installGearSets` (the gear-set engine's per-player look record, `GearSetLooksComponent`,
  registered first and unconditionally with its connect hook, then the engine hung on that bridge
  as one post-apply listener, its LATE player-ready hydrate, its respawn recompute and its one
  eviction, the entity leaving its store for any reason) / `registerPerformerIdentity` /
  `registerFlairs` / `registerPlayerIdentity` / `registerOverheadIndicators`, called from the wiring
  root's ordered list).
  - [`entity/performer/`](src/main/java/com/ziggfreed/common/entity/performer/CLAUDE.md) - the
    `StationPerformer` contract (`HolderPerformer`/`NpcRolePerformer` backends,
    `PerformerIdentityComponent` + `PerformerReconciler`).
  - `entity/flair/` - `ZigFlairComponent`, the registered per-player unlocked-flair id set the
    library persists so a granting mod and a rendering mod meet over one record (ids lower-cased at
    write, an id carrying `|` or `:` refused). The WRITE path is zc-objectives'
    `objectives/flair/FlairUnlocks` (the `Flair` reward kind and `/zigflair` both go through it),
    which fires `ZigFlairChangedEvent` on the engine bus and the `Flair_Unlocked` toast on every
    real change; this module keeps only the record and its refusal rule. No router of its own (one
    file).
- `factor/` - `HytaleFactors` only, the portable `hytale:` factor standard library: thirteen straight
  reads of engine data about the context's own subject - its stat channels, what it is holding, and
  (`hytale:permission`) the permission nodes its connection holds - or about the context's ITEM leaf
  (`hytale:item_quality` / `item_level` / `item_durability_percent` / `item_stat`, each through
  `entity/ItemReadings`, each null where the context carries no item). This is one half of a deliberate
  split package; the domain-free model
  (`FactorContext`/`FactorProvider`/`FactorRegistry`/`FactorCondition`) lives in
  [`zc-core`'s `factor/`](../zc-core/src/main/java/com/ziggfreed/common/factor/CLAUDE.md), whose
  router carries the shared vocabulary. No router of its own (one file).
- `recipe/` - the native RECIPE INDEX. `RecipeIndex.live().catalog()` is an immutable
  `RecipeCatalog` over the engine's whole `CraftingRecipe` store, where standalone recipe files
  (vanilla's `Salvage_<ItemId>` set) and item-inline recipes (`<ItemId>_Recipe_Generated_0`, with
  `ownerItemId` set) are one `NativeRecipe` shape carrying FULL `RecipeMaterial` quantities (the
  first authored route kept in the engine's order: item, tag, resource family), its `RecipeBench`es,
  its primary output and craft time. Lookups: `atBench(benchId)`, `consuming(itemId)` (through the
  shared zc-core `ItemMatch`, after the engine's own output exclusions), `producing(itemId)`,
  `craftingRecipeOf(itemId)`, `recipe(id)`, `all()`, every list ordered by id. Built lazily from the
  asset map (never from the engine's per-bench registries, whose rebuild order against another
  mod's reload handler is not guaranteed), invalidated on every `CraftingRecipe` and `Item` load and
  removal by the root's `FrameworkAssetRegistrar`, and `generation()` is the number a consumer's
  derived cache keys on. The catalog is PURE (plain values in); `LiveRecipes` is the thin engine
  adapter, and a tag line's name is recovered from the loaded items' own raw tag keys, since the
  engine's `MaterialQuantity` keeps only the tag's index. No router of its own; the class javadoc
  carries the detail.
- [`stats/`](src/main/java/com/ziggfreed/common/stats/CLAUDE.md) - `EquipStatBridge` (held/armor/
  offhand `StackStats` -> native `EntityStatMap` modifiers, plus `equippedSnapshot`, what the entity
  has on by item id), `EquippedSnapshot`, `StatMirror`, `StatChannelAudit`, `StatIndexCache`. This is
  the ECS-bridging half of the `stats` split package; the pure item-metadata record `StackStats`
  itself lives in `zc-core`, described in this router's own Conventions section for why the two
  halves stay apart.
  - `stats/gearset/` - the GEAR-SET engine over the bridge, described in the same router:
    `GearSetAsset` (the `Server/ZiggfreedCommon/GearSets/` store, `GearSetConfig`,
    `GearSetOwnerLayers`), `GearSets` (the one post-apply listener, the recompute, the lifecycle,
    with `GearSetLifecycleSystems` for the respawn and the entity leaving its store),
    `GearSetLooksComponent` (the per-player saved look record, the looks the engine answers for),
    the pure `GearSetDecision` / `GearSetPlan` / `GearSetIndex` cores over `GearSetKeys`, the
    `GearSetEffects` seam, the `GearSetEvents` family with `ZigGearSetTierChangedEvent`, and
    `GearSetValidator`. It reads engine stat, item and effect data, which is why it lives here and
    not beside `StackStats` in zc-core.

## Shipped resources

None. This module carries no `Server/` or `Common/UI/` content.

## Conventions

World-thread discipline throughout (every method touching a `Store`/`Ref`/`Holder` hops via
`world.execute` off-thread and is try-guarded). `stats/` and `zc-core`'s `counter/` never merge: a
"how many times has this subject done X" number is a tally (`counter/`), a "what does this sword
add to Attack Damage" number is an item-carried stat (`stats/`) - see this package's own router for
the rule stated in full. The performer contract's mutating methods each take a FRESH per-call
`ComponentAccessor` the caller threads from its own current frame, never a stashed one.

## Tests

37 files: the stat bridge (`EquipStatBridgeTest`, `EquipStatBridgeAppliedListenerTest`,
`StatMirrorTest`, `StatChannelAuditTest`, `EquippedSnapshotTest`), the gear-set engine, all on plain
ids with no engine item anywhere (`GearSetAssetCodecTest`: the native block through the own leaf, a
`$Comment` inside the map, `Bonuses` replacing and `Text` merging under `Parent`, an unknown word
failing the read; `GearSetDecisionTest`: each minimum independent of the others (a held copy of a worn piece
counts once toward `Pieces` and never satisfies `Held`); `GearSetIndexTest`: the candidates found
one lookup per item, in id order, and the looks a hydrate answers for, each once in set-id order, a
disabled set's included; `GearSetAppliedTest`: a world change forgets the row, so the recompute
after it fires no flip and a return from an instance is a quiet hydrate; `GearSetLifecycleTest`: a
look cleared by death and respawn comes back with the row intact and no notice, none on a corpse,
every removal forgets the row (a recompute queued before the disconnect cannot leave one behind,
driven with `UNLOAD`, the disconnect's real reason), a look the saved record names comes off at
the hydrate with no notice though no current set names it, and a look whose remove failed stays
recorded and is tried again until it comes off, unless its effect asset is gone; `GearSetLooksComponentTest`: the saved
record's codec round trip under `Effects` and its whole-replacement rule; `GearSetPlanTest`: the
diff and the sweep
against a lookup that records every key asked, so a `ziggfreedcommon:` key or a consumer's own is
provably never touched; `GearSetValidatorTest`: one case per code, and a member spelled in
another case is not unknown; `GearSetEventsTest`: the
flip arithmetic and the fire through `publishTo`; `GearSetEffectsTest`: the three-member seam reporting once, through `apply` and `remove` as well as `has`;
`GearSetEditorSchemaTest`: items on arrays, the closed words, `Enabled` default, `$Comment` in the
map), the factor standard library (`HytaleFactorsTest`, which
drives the item family over real engine stacks), the one item reader (`ItemReadingsTest`: the held-tool
paths pinned byte-identical to 2.1.x, and the quality split on a stack made before its item's quality
moved, plus `ItemMetadataKeysTest`: the metadata keys of a bare, a metadata-carrying, a stamped and
an unreadable stack and the undeclared ones among them, over the shared `TestItems` fixture: real `Item` / `ItemQuality` values filled through their
protected fields and real `ItemStack`s made through the engine's own constructors, since a unit JVM
has no item store; `TestAssetStores` seeds the live quality and stat-channel maps the engine's own way
for `HytaleFactorsTest`'s value tests), the recipe index (`RecipeCatalogTest` over hand-written
standalone and inline recipes, `RecipeIndexTest` for the build / invalidate / generation lifecycle
over an injected source), the
per-player flair set (`ZigFlairComponentTest`), the puppet/performer stack
(`PlayerPuppetServiceTest`, `PuppetNavTest`, `PuppetWalkMathTest`, `ItemPropEntityServiceTest`,
`PerformerContractTest`, `PerformerIdentityCodecTest`, `PerformerReconcileTest`,
`PerformerWalkMathTest`), and `HeldItemUtil`'s tool-power selection (`ToolPowerSelectionTest`,
`ToolTierSelectionTest`, both fixture-authored per their own file javadoc so a real tool's balance
pass never drags a test with it). The engine-touching paths (puppet spawn/despawn, performer presentation) await maintainer
in-game smoke per their package router; the pure decision cores (walk math, reconcile policy,
tool-power selection) are fully unit-tested.

**Two test tasks** (`gradle/zc-module.gradle`, since 2.2.0). A real `Item`, `ItemStack` or
`ItemQuality`, and a seeded engine asset store, can only be built under the engine's own
`HytaleLogManager`, so every test that builds one is tagged `engine-items` and runs in the
`engineItemTest` task, which starts under that manager: `ItemReadingsTest` and
`ItemMetadataKeysTest` as a whole, and in
`HytaleFactorsTest` the item-family tests plus `aBlankParamIsNotEnoughForItemStatEvenWithAnItem`
(the blank-Param case that carries a real stack; the no-item blank-Param cases stay untagged). Everything else runs in the default `test` task with no
log manager, exactly as a consumer mod's test JVM does, which is where the logging guards are
exercised; an untagged test that builds an item fails there on the engine's class-init error.
`check`, so `gradlew build`, runs both.
