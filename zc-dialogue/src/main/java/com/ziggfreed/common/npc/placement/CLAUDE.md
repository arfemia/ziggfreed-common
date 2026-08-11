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
  [`WorldSelector`](../../../../../../../../../zc-world/src/main/java/com/ziggfreed/common/world/WorldSelector.java) - a null/empty selector defaults to
  `Names:["primary"]` **at this read site**); `Anchor{WorldSpawn,Coords,Structure,Zone,Custom}`
  (nullable ORTHOGONAL groups, never a placement-mode enum); `Requires{Conditions[]}`;
  `Limits{SpawnChance,MaxPerWorld,OncePerWorld}`; `Lifecycle{KeepAlive,Respawn,Fortify,
  FortifyHealth}` (ALL opt-in, default false - each costs a pinned chunk / a re-place / a health
  pool); `Interact{Dialogue,Bindings}`.
- **`Interact.Bindings` is a MAP keyed by channel, decoded through
  [`InheritMapCodec`](../../../../../../../../../zc-core/src/main/java/com/ziggfreed/common/codec/InheritMapCodec.java) - NOT an array.** An array leaf under
  `appendInherited` is a SINGLE leaf, so authoring it at all would drop every inherited entry and
  break per-binding `Parent` override, which is the flagship authoring shape. Each entry is
  [`PlacementBinding`](PlacementBinding.java) `{Param?,Value?,Amount?}`. `NpcPlacementAssetCodecTest`
  proves the per-key merge (a child authoring one channel keeps the parent's others), because that
  is what the map buys.
- **`Requires.Conditions` is the shared [`factor/FactorCondition`](../../../../../../../../../zc-core/src/main/java/com/ziggfreed/common/factor/CLAUDE.md)**
  `{Factor,Param?,Min?,Max?}` - the read-side twin of a binding: a binding hands an opaque payload
  OUT, a condition asks a registered provider for a number. Same leaf, same keys, same meaning a
  dialogue `Factor` condition has, so authored JSON is unchanged. **Fails closed**: an unregistered
  factor cannot RESOLVE at all (never a zero), and a `FactorCondition` rejects an unresolvable value
  whatever its bounds say - including the bounds-less presence-check form, which is exactly the
  shape "only where this mod is installed" is written in.
- **[`NpcPlacementConfig`](NpcPlacementConfig.java)** - the `defaults < pack < owner` fold. Every
  merge clears the sweep debounce + the position cache, so a reload lands on the next sweep.
  Registered by [`../../asset/FrameworkAssetRegistrar`](../../../../../../../../../src/main/java/com/ziggfreed/common/asset/FrameworkAssetRegistrar.java).

## The open registries (the third/fourth-party story)

Each is JVM-global, case-insensitive, last-write-wins, and warns ONCE per unknown id.

- **[`PlacementRegistryLedger<T>`](PlacementRegistryLedger.java)** is this engine's naming of the
  shared [`registry/RegistryLedger`](../../../../../../../../../zc-core/src/main/java/com/ziggfreed/common/registry/CLAUDE.md):
  a one-line subclass that fixes the `[placement]` log label, so an overwrite warning says which
  engine it came from. Every semantic lives in the parent (per id: a value, its owning mod name, a
  failure count and the latest failure message; `put` overwrite-warns ONCE per id by IDENTITY not
  equality, so a consumer re-running its own `setup()` with the SAME instance is silent; `info()`
  is the snapshot an admin channels-list command reads), and `RegistrationInfo` is INHERITED - a
  qualified `PlacementRegistryLedger.RegistrationInfo` resolves as before, while an `import`
  statement must name the declaring `RegistryLedger`. The three registries below hold no map of
  their own.
- **[`NpcPlacementBindings`](NpcPlacementBindings.java)** - `register(namespace, handler)` (owner
  `"unattributed"`) or `register(namespace, owner, handler)` + `bindingsFor(placementId)` /
  `bindingValue(placementId, channelId)` / `info()`. `byNamespace(map)` is the PURE split (a key
  with no colon has no owner and is dropped); `byNamespace(map, placementId)` additionally
  warns ONCE per `(placementId, key)` at the drop site - a colon-less key used to vanish with
  zero signal, even at runtime. A handler ([`PlacementInteractHandler`](PlacementInteractHandler.java))
  gets EVERY binding on its namespace in ONE call, so a consumer needing two channels together
  reads both without a second lookup. An unclaimed namespace is one WARN and then silence, never
  a hard fail.
- **[`PlacementFactorRegistry`](PlacementFactorRegistry.java)** - the static facade over ONE shared
  [`factor/FactorRegistry`](../../../../../../../../../zc-core/src/main/java/com/ziggfreed/common/factor/CLAUDE.md)
  instance (process-wide because placement CONTENT is: one asset store, one sweep, one ledger).
  `register(factorId[, owner], provider)` takes the shared `factor.FactorProvider`; `resolve`
  answers a **nullable** `Double`, `registry()` exposes the instance, `info()` the ledger snapshot.
  `firstFailure(requires, placementId, world, store)` evaluates a whole `Requires` through
  `FactorConditions`, building a context with the placement id as its opaque PAYLOAD (read it back
  with `ctx.payload(String.class)`) and NO subject entity - a placement gate is asked before
  anything stands there to ask about. **The gate-never-silently-opens rule**: unregistered,
  throwing, non-finite and cannot-answer all resolve to nothing, and nothing fails every condition
  shape, so a bounds-less presence check on a missing mod's factor keeps the placement absent.
- **[`AnchorResolverRegistry`](AnchorResolverRegistry.java)** - `register(providerId, resolver)` or
  `register(providerId, owner, resolver)`, `info()`, backing `Anchor.Custom{Provider,Params}`, so a
  fourth party adds an anchor with ZERO common changes. Returned positions are re-stamped `CUSTOM`
  with the provider id folded into the instance id (two providers can never collide). **A
  resolver's `instanceId` must be STABLE across restarts** - a bare loop index changes with
  ordering and mints a duplicate NPC.
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
  [`../../util/JsonOverrideWriter`](../../../../../../../../../zc-core/src/main/java/com/ziggfreed/common/util/JsonOverrideWriter.java) (atomic, `$Comment` and
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
  **A base role's raw JSON body comes from one of two sources**: a consumer registers its OWN base
  role JSON in Java (`registerBaseRoleResource(id, Class, path)`), or a pack author ships it as an
  [`NpcBaseRoleAsset`](NpcBaseRoleAsset.java) at `Server/ZiggfreedCommon/NpcBaseRoles/<baseId>.json`
  (folded by [`NpcBaseRoleConfig`](NpcBaseRoleConfig.java) into `registerBaseRoleFromAsset`) - common
  ships no roles of its own either way. **On a same-id collision the ASSET WINS** over a
  Java-registered base (defaults < pack < owner precedence), logged once at INFO naming both; a
  hot re-fold of the SAME asset id is not treated as a collision. A base role with no usable
  `Payload` registers nothing - see [`NpcBaseRoleValidator`](NpcBaseRoleValidator.java). Call
  `generateAndRegister(version)` once per boot AFTER the asset fold and BEFORE any world streams
  chunks.
- **[`NpcPlacementValidator`](NpcPlacementValidator.java)** - the findings that are otherwise
  SILENT (an NPC that never appears is indistinguishable from one you have not walked to): blank
  role, an anchor group with no usable params, `SpawnChance <= 0`, an unregistered `Custom.Provider`,
  an unregistered `Requires.Factor`, a `Where` naming no known selector, an `ExcludeNames`-only
  selector, a colon-less `Interact.Bindings` key (`BINDING_KEY_NO_NAMESPACE`), and an authored
  binding namespace no handler claimed (`UNCLAIMED_BINDING_NAMESPACE`). Neutral `Issue` values (the
  `world/WorldSelectorValidator` idiom). Several checks read what is REGISTERED, so run the audit at
  first player-ready, not at plugin setup. [`NpcBaseRoleValidator`](NpcBaseRoleValidator.java) is
  the small sibling for base roles: an empty or non-object `Payload` (`EMPTY_BASE_ROLE_PAYLOAD`).

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
covers union / collapse order / cross-union capacity / roll determinism; `PlacementRegistryLedgerTest`
covers identity-vs-equality overwrite warnings, failure counting and the `info()` snapshot through
the subclass (the base contract itself is `zc-core`'s `RegistryLedgerTest`);
`NpcPlacementValidatorTest` covers the colon-less-key and unclaimed-namespace findings;
`NpcBaseRoleTest` covers the asset fold, the asset-wins-over-Java collision, and the
empty-payload finding; `PlacementRegistryTest`
covers fail-closed factors (including the bounds-less presence check and the placement-id payload),
no-position anchors and the namespace split;
`NpcPlacementAssetCodecTest` proves the per-key `Bindings` override. Every new CODEC is asserted in
[`AssetCodecInitTest`](../../../../../../../../../src/test/java/com/ziggfreed/common/asset/AssetCodecInitTest.java).
The engine-touching paths (spawn, sweep, pin, role generation) have no unit coverage and land behind
in-game smoke, matching the rest of the mod's split.
