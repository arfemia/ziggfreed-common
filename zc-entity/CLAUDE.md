# CLAUDE.md - zc-entity

Puppets, performers, per-player flair, and the item-carried stat bridge: the entity-presentation and
entity-stat primitives that need real engine entity/item data, split out from the domain-free
`factor/` and `stats/` cores that live in `zc-core`.

## Build

Part of the thirteen-module `ziggfreed-common` build (`gradle/zc-module.gradle` convention, Java 25,
compiles as `:zc-entity`). See the root [`CLAUDE.md`](../CLAUDE.md) for the aggregate build.

## Dependencies

- **Depends on**: `zc-core` only. The world-eviction seam a performer's per-world bookkeeping
  registers against is the `cast.WorldEvictors` zc-core primitive, which is why this module rests
  on core alone rather than needing an edge to `zc-cast`.
- **Depended on by**: `zc-objectives`, `zc-world`.
- **Reverse-edge trap**: none declared today. This module carries no domain vocabulary of its own
  (it is entity-presentation + item-stat plumbing), so an edge upward to a domain module (loot,
  progression, dialogue) would be the first sign something domain-specific had leaked in here.

## Packages

- [`entity/`](src/main/java/com/ziggfreed/common/entity/CLAUDE.md) - `PlayerModelService`,
  `PlayerPuppetService` (clone a live `PlayerSkin` onto a networked spawned entity, held-item
  mirroring, the `Scale` self-hide/reveal pair), `ItemPropEntityService`, `PuppetNav` (bounded A*
  over `CollisionModule`), `HeldItemUtil` (a held tool's gather-power SPREAD selection, e.g. picking
  the right power out of a dozen authored on one item), `PlayerIdentityCache` (resolves a player's
  UUID on the world thread, so an off-thread engine callback holding a bare `Player` never needs the
  deprecated-for-removal `Entity.getUuid()`), and `EntityBootstrap` (this module's own three
  `setup()` registration phases: `registerPerformerIdentity` / `registerFlairs` /
  `registerPlayerIdentity`, called from the wiring root's ordered list).
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
- `factor/` - `HytaleFactors` only, the portable `hytale:` factor standard library: nine straight
  reads of engine data about the context's own subject - its stat channels, what it is holding, and
  (`hytale:permission`) the permission nodes its connection holds. This is one half of a deliberate
  split package; the domain-free model
  (`FactorContext`/`FactorProvider`/`FactorRegistry`/`FactorCondition`) lives in
  [`zc-core`'s `factor/`](../zc-core/src/main/java/com/ziggfreed/common/factor/CLAUDE.md), whose
  router carries the shared vocabulary. No router of its own (one file).
- [`stats/`](src/main/java/com/ziggfreed/common/stats/CLAUDE.md) - `EquipStatBridge` (held/armor/
  offhand `StackStats` -> native `EntityStatMap` modifiers), `StatMirror`, `StatChannelAudit`,
  `StatIndexCache`. This is the ECS-bridging half of the `stats` split package; the pure
  item-metadata record `StackStats` itself lives in `zc-core`, described in this router's own
  Conventions section for why the two halves stay apart.

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

16 files: the stat bridge (`EquipStatBridgeTest`, `EquipStatBridgeAppliedListenerTest`,
`StatMirrorTest`, `StatChannelAuditTest`), the factor standard library (`HytaleFactorsTest`), the
per-player flair set (`ZigFlairComponentTest`), the puppet/performer stack
(`PlayerPuppetServiceTest`, `PuppetNavTest`, `PuppetWalkMathTest`, `ItemPropEntityServiceTest`,
`PerformerContractTest`, `PerformerIdentityCodecTest`, `PerformerReconcileTest`,
`PerformerWalkMathTest`), and `HeldItemUtil`'s tool-power selection (`ToolPowerSelectionTest`,
`ToolTierSelectionTest`, both fixture-authored per their own file javadoc so a real tool's balance
pass never drags a test with it). The engine-touching paths (puppet spawn/despawn, performer presentation) await maintainer
in-game smoke per their package router; the pure decision cores (walk math, reconcile policy,
tool-power selection) are fully unit-tested.
