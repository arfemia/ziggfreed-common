# npc/placement/ - the NPC placement engine

Router for `com.ziggfreed.common.npc.placement`. ONE generic engine for "put an NPC somewhere, make
press-F do something, and keep exactly one of it standing", with open registries so a pack author
and a fourth-party mod can both compose against it without Java. Content is authored at
`Server/ZiggfreedCommon/NpcPlacements/<id>.json`; common ships ZERO placement content.

## Read this first: the two authorities

> **NEVER place from absence alone.** A chunk unload REMOVES an entity from the store and restores
> it when the chunk ticks again, so a `forEachEntityParallel` sweep cannot tell "never placed" from
> "placed, chunk asleep". Placing on absence spawns a second NPC every time a player walks back
> into range. Placement requires `ledgerMiss && anchorChunkLoaded`. `Lifecycle.KeepAlive` masks the
> problem for one placement and for nothing else, so it is not a fix.

- **[`PlacedNpcComponent`](PlacedNpcComponent.java)** (`ZiggfreedCommon:PlacedNpc`) is the
  **despawn/orphan authority**: a sweep over it answers "what is standing that should not be"
  (placement deleted / gate denies / `Where` no longer matches). Registered once by
  `ZiggfreedCommonPlugin`, attached on the **pre-add `Holder`** (no live-ref race). Its pure
  snapshot is [`PlacedNpcIdentity`](PlacedNpcIdentity.java).
- **[`NpcPlacementLedger`](NpcPlacementLedger.java)** is the **place authority**: a persisted
  `(world | placementId | anchorKey) -> uuid` row that survives both the chunk sleeping and the
  server restarting. `mods/ziggfreedcommon/npc-placement-ledger.json`.

Neither can do the other's job, which is why there are two.

## The asset

- **[`NpcPlacementAsset`](NpcPlacementAsset.java)** - Pattern A, **every leaf `appendInherited`**,
  so a `Parent`-linked child overriding one leaf keeps every untouched sibling. Groups: top-level
  `Enabled`; `Identity{Role,BaseRole,Appearance,NameKey,HintKey}`; `Where` (a
  [`WorldSelector`](../../world/WorldSelector.java) - a null/empty selector defaults to
  `Names:["primary"]` **at this read site**); `Anchor{WorldSpawn,Coords,Structure,Zone,Custom}`
  (nullable ORTHOGONAL groups, never a placement-mode enum); `Requires{Conditions[]}`;
  `Limits{SpawnChance,MaxPerWorld,OncePerWorld}`; `Lifecycle{KeepAlive,Respawn,Fortify,
  FortifyHealth}` (ALL opt-in, default false - each costs a pinned chunk / a re-place / a health
  pool); `Interact{Dialogue,Bindings}`.
- **`Interact.Bindings` is a MAP keyed by channel, decoded through
  [`InheritMapCodec`](../../codec/InheritMapCodec.java) - NOT an array.** An array leaf under
  `appendInherited` is a SINGLE leaf, so authoring it at all would drop every inherited entry and
  break per-binding `Parent` override, which is the flagship authoring shape. Each entry is
  [`PlacementBinding`](PlacementBinding.java) `{Param?,Value?,Amount?}`. `NpcPlacementAssetCodecTest`
  proves the per-key merge (a child authoring one channel keeps the parent's others), because that
  is what the map buys.
- **[`PlacementCondition`](PlacementCondition.java)** `{Factor,Param?,Min?,Max?}` is the read-side
  twin of a binding: a binding hands an opaque payload OUT, a condition asks a registered provider
  for a number. **Fails closed** (unregistered resolves 0).
- **[`NpcPlacementConfig`](NpcPlacementConfig.java)** - the `defaults < pack < owner` fold. Every
  merge clears the sweep debounce + the position cache, so a reload lands on the next sweep.
  Registered by [`../../asset/FrameworkAssetRegistrar`](../../asset/FrameworkAssetRegistrar.java).

## The open registries (the third/fourth-party story)

Each is JVM-global, case-insensitive, last-write-wins, and warns ONCE per unknown id.

- **[`NpcPlacementBindings`](NpcPlacementBindings.java)** - `register(namespace, handler)` +
  `bindingsFor(placementId)` / `bindingValue(placementId, channelId)`. `byNamespace(map)` is the
  PURE split (a key with no colon has no owner and is dropped). A handler
  ([`PlacementInteractHandler`](PlacementInteractHandler.java)) gets EVERY binding on its namespace
  in ONE call, so a consumer needing two channels together reads both without a second lookup. An
  unclaimed namespace is one WARN and then silence, never a hard fail.
- **[`PlacementFactorRegistry`](PlacementFactorRegistry.java)** - `register(factorId, provider)`
  backing `Requires.Conditions`. Unregistered or throwing resolves 0, and since a condition gates on
  bounds the placement simply does not appear. `firstFailure(...)` evaluates a whole `Requires`.
- **[`AnchorResolverRegistry`](AnchorResolverRegistry.java)** - `register(providerId, resolver)`
  backing `Anchor.Custom{Provider,Params}`, so a fourth party adds an anchor with ZERO common
  changes. Returned positions are re-stamped `CUSTOM` with the provider id folded into the instance
  id (two providers can never collide). **A resolver's `instanceId` must be STABLE across restarts**
  - a bare loop index changes with ordering and mints a duplicate NPC.
- **[`PlacementGates`](PlacementGates.java)** - the ordered veto chain over
  [`PlacementGate`](PlacementGate.java) (`GateContext{placement, world, worldNames, store}` ->
  `GateVerdict{allowed, reasonKey}`). **Any deny wins and the FIRST deny is reported**, so ordering
  matters; the three built-ins are the asset's `Enabled`, the owner override, then the authored
  `Requires`. A throwing gate is skipped, not treated as a deny. **A deny DESPAWNS the standing
  NPC on the next sweep** - that is what makes an admin switch immediate.

## The runtime

- **[`NpcPlacementReconciler`](NpcPlacementReconciler.java)** - the correctness core. Two PURE
  decision cores (`decideResident` -> KEEP/DESPAWN/REBIND, `decidePlace` -> PLACE/REPLACE/SKIP) plus
  a three-pass live `sweep(world, store)`: **DESPAWN** (component-authoritative, frees a
  `MaxPerWorld` slot in the same pass) -> **HEAL** (a ledger row whose entity lacks the stamp is
  adopted, not duplicated) -> **PLACE** (ledger-authoritative). An **in-flight claim set** keyed
  `(world, placementId, anchorKey)` guards two players entering a fresh instance in one tick (the
  first add is invisible until the command buffer flushes). A **per-world debounce latch** keeps a
  world entry from running a full parallel scan every time; it is cleared by an asset reload, a gate
  change, a new marker sighting, a zone discovery, and world removal. `requestSweep` is debounced,
  `forceSweep` is not; **both defer through `world.execute`**, because spawning an entity from
  inside a system throws and the throw becomes a silently missing NPC.

  | resident | gate denied / placement gone / world no longer matches | -> DESPAWN |
  |---|---|---|
  | resident | correct but no ledger row | -> REBIND (adopt) |
  | resident | everything agrees | -> KEEP |
  | absent | **ledger hit + chunk ASLEEP** | -> **SKIP (the double-place regression)** |
  | absent | ledger miss + chunk ASLEEP | -> SKIP |
  | absent | ledger miss + chunk loaded + under capacity | -> PLACE |
  | absent | ledger hit + chunk loaded + entity gone + `Respawn` | -> REPLACE |

- **[`NpcPlacementService`](NpcPlacementService.java)** - thin policy over
  [`../NpcSpawnService`](../NpcSpawnService.java) (which gained an ADDITIVE `preAdd`+`postSpawn`
  overload for the no-race stamp attach): `place`/`despawn`/`releaseInstance`/`fortify`/`pinChunk`/
  `isChunkLoaded`. `fortify` raises max health enormously because a role's `Invulnerable` flag is
  NOT consulted by a direct stat-map health write, so a "true damage" effect can otherwise kill a
  service NPC and take every player's access to it with it.
- **[`PlacementKeepAlivePins`](PlacementKeepAlivePins.java)** - `addKeepLoaded` is REFERENCE
  COUNTED with no auto-release, so a sweep re-pinning a standing NPC would raise the count forever.
  Owns `world -> chunk -> Set<placementKey>`: **pin on FIRST insert, unpin on LAST removal**, whole
  world dropped by a `WorldEvictors` evictor (which is also what stops an instance teardown leaking
  pins). `applyClaim` is the PURE edge core.
- **[`PlacementAnchors`](PlacementAnchors.java)** - the union/limits engine. Several groups produce
  the UNION, each an independent instance keyed `(kind, instanceId)`; `MaxPerWorld` counts ACROSS the
  union; `OncePerWorld` collapses to the first in DECLARATION order (WorldSpawn, Coords, Structure,
  Zone, Custom) so the survivor is readable off the file, not dependent on chunk-load timing;
  `SpawnChance` is a DETERMINISTIC `SplitMix64` roll over `(worldSeed, placementId, anchorKey)`,
  never `java.util.Random`.
- **[`AnchorPosition`](AnchorPosition.java)** - `(kind, instanceId, x, y, z, yaw)`; `anchorKey()` is
  a PERSISTED format (it is a ledger key component), so changing it orphans every row.
- **[`StructureAnchorIndex`](StructureAnchorIndex.java)** + **[`PlacementMarkerSystem`](PlacementMarkerSystem.java)**
  + **[`StructureMarkerSightings`](StructureMarkerSightings.java)** - the structure driver. A marker
  is only knowable when its chunk loads, so the system records sightings into the live index (what
  anchors read) and the bounded ring buffer (what an author reads to discover real marker ids), then
  clears the debounce and asks for a sweep. Keyed by the stable `prefabInstanceId`. A marker with no
  `FromPrefabInstance` is ignored (its anchor key could not be stable). The index is transient by
  design: an unknown marker and an unloaded one lead to the same correct decision to do nothing.
- **[`ZoneAnchorIndex`](ZoneAnchorIndex.java)** - `notifyZoneDiscovered(world, store, zoneName,
  regionName, x, y, z)`. **The engine owns the anchor; the consumer supplies the trigger** (only a
  consumer knows what a zone is and what counts as discovering one). A discovery kicks a sweep.
- **[`NpcPlacementPositionCache`](NpcPlacementPositionCache.java)** - keyed `(worldName,
  placementId, anchorKey)`, **never by placement id alone**: two concurrent instances of one dungeon
  share a placement id, and a single-key cache would point a player in instance A at instance B. NOT
  an authority - it exists so a quest-waypoint feature can point at an NPC whose chunk is asleep.
- **[`NpcPlacementOverrides`](NpcPlacementOverrides.java)** - the owner switch at
  `mods/ziggfreedcommon/npc-placements.json`, `{"<key>": {"enabled": false}}`. **One key grammar,
  no nested sections**: a placement id, a trailing-`*` prefix (which IS the per-mod section), or the
  bare `*`. Most specific wins (exact > longest prefix > `*`), so `{"*":{"enabled":false},
  "mmo_hub":{"enabled":true}}` leaves exactly the hub standing. Writes through
  [`../../util/JsonOverrideWriter`](../../util/JsonOverrideWriter.java) (atomic, `$Comment` and
  siblings preserved); a malformed file is never overwritten. **A caller must force a sweep after
  writing** or the switch waits for the next restart.
- **[`ActionPlacementInteract`](ActionPlacementInteract.java)** + its
  [builder](BuilderActionPlacementInteract.java), registered `"ZigPlacementInteract"` by
  [`PlacementNpcActions`](PlacementNpcActions.java) - ONE press-F action for every placement. It
  reads the NPC's own stamp, opens `Interact.Dialogue`, and fans `Interact.Bindings` out per
  namespace. Reading identity off the ENTITY is what lets one base role serve every placement in the
  server; a role cannot carry per-placement data, so an action that encoded a destination would need
  one role per destination.
- **[`NpcRoleGenerator`](NpcRoleGenerator.java)** - clone a base role, substitute
  `Appearance`/`NameTranslationKey.Value`/every `Hint` (a recursive walk, so a base that grows a
  second interaction still gets a correct hint), register as a `PackSource.RUNTIME` directory pack.
  **A consumer registers its OWN base role JSON** (`registerBaseRoleResource(id, Class, path)`) -
  common ships no roles. Call `generateAndRegister(version)` once per boot AFTER the asset fold and
  BEFORE any world streams chunks.
- **[`NpcPlacementValidator`](NpcPlacementValidator.java)** - the findings that are otherwise
  SILENT (an NPC that never appears is indistinguishable from one you have not walked to): blank
  role, an anchor group with no usable params, `SpawnChance <= 0`, an unregistered `Custom.Provider`,
  an unregistered `Requires.Factor`, a `Where` naming no known selector, an `ExcludeNames`-only
  selector. Neutral `Issue` values (the `world/WorldSelectorValidator` idiom). Several checks read
  what is REGISTERED, so run the audit at first player-ready, not at plugin setup.

## Wiring (what `ZiggfreedCommonPlugin` owns)

`PlacedNpcComponent.register(...)`, `PlacementNpcActions.register()`, the `PlacementMarkerSystem`,
the overrides + ledger load, **and common's own `AddWorldEvent`/`RemoveWorldEvent` listeners**.
That last one is not tidiness: eviction used to be driven only from CONSUMER listeners, so two
consumer mods fired the `WorldEvictors` fan-out twice per world - harmless for a `map::remove`,
corrupting for a refcounted unpin. `WorldEvictors.onWorldRemoved` also gained an idempotence guard
(bounded, keyed by world NAME so a removed world object is not held alive), cleared by
`onWorldAdded`.

## Tests

Pure decision cores only, never balance numbers. `NpcPlacementReconcilerTest` leads with the named
double-place regression; `PlacementGateChainTest` covers any-deny-wins + first-deny-reported +
override precedence; `PlacementKeepAlivePinsTest` covers the pin/unpin edges; `PlacementAnchorsTest`
covers union / collapse order / cross-union capacity / roll determinism; `PlacementRegistryTest`
covers fail-closed factors, no-position anchors and the namespace split;
`NpcPlacementAssetCodecTest` proves the per-key `Bindings` override. Every new CODEC is asserted in
[`AssetCodecInitTest`](../../../../../../../test/java/com/ziggfreed/common/asset/AssetCodecInitTest.java).
The engine-touching paths (spawn, sweep, pin, role generation) have no unit coverage and land behind
in-game smoke, matching the rest of the mod's split.
