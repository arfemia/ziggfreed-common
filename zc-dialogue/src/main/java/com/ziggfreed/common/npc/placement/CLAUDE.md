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
  so a `Parent`-linked child overriding one leaf keeps every untouched sibling. A `Parent` value
  must spell the target's EXACT filename (minus `.json`, case and all): same-pack parent resolution
  is case-sensitive in the engine, and a mismatched ref drops the child at load with a boot
  validation error. Groups: top-level
  `Enabled`; `Identity{Role,BaseRole,Appearance,NameKey,HintKey,NpcId,Aliases}` (Appearance is the nested
  [`AppearanceSpec`](AppearanceSpec.java) GROUP, not a string; **`BaseRole` names a native
  parameterized TEMPLATE role directly**, and the generated role is a variant of it); `Where` (a
  [`WorldSelector`](../../../../../../../../../zc-world/src/main/java/com/ziggfreed/common/world/WorldSelector.java) - a null/empty selector defaults to
  `Names:["default"]` **at this read site**); `Anchor{WorldSpawn,Coords,Structure,Zone,Custom}`
  (nullable ORTHOGONAL groups, never a placement-mode enum); `Requires{Conditions[]}`;
  `Limits{SpawnChance,ChanceFormula,MaxPerWorld,OncePerWorld}`; `Lifecycle{KeepAlive,Respawn,Fortify,
  FortifyHealth}` (ALL opt-in, default false - each costs a pinned chunk / a re-place / a health
  pool); `Interact{Dialogue,Bindings}`.
- **`Identity.NpcId` + `Identity.Aliases` are what CONTENT calls this character** - a quest's giver,
  a hand-in target, a talk objective, the waypoint marked for it. Both OPTIONAL: with no `NpcId` the
  placement answers to its own placement id, so putting an NPC somewhere is already enough to make it
  nameable. `Aliases` is one leaf, so an authored list replaces the parent's whole list rather than
  adding to it. Identity lives HERE rather than inside `Interact` because it is who the NPC IS, not
  what pressing F does - a placement with no `Interact` block still has a name. The resolution rules,
  the primary-versus-alias asymmetry and the reverse index are
  [`../NpcIdentities`](../CLAUDE.md).
- **[`AppearanceSpec`](AppearanceSpec.java) is `Identity.Appearance`: orthogonal knobs behind ONE
  exclusive choice.** `Model` uses an existing Model asset as-is; `Base` CLONES one and the override knobs re-dress the clone (`Texture`, `GradientSet`/
  `GradientId`, `Scale`, `Particles[]` of `{SystemId,TargetNodeName,Color,Scale,PositionOffset,
  RotationOffset,DetachedFromModel}`). Both authored is `APPEARANCE_MODEL_AND_BASE`. `Equipment
  {Armor,Hotbar,OffHand,DefaultOffHandSlot}` is orthogonal to the choice because it rides the ROLE
  (`Armor`/`HotbarItems`/`OffHandItems`/`DefaultOffHandSlot`), not the model, so it needs no `Base`.
  `Scale` is emitted as `MinScale == MaxScale` so the engine's own spawn-time draw yields a constant
  and persists it per instance (`MdlScl`) - **never `EntityScaleComponent` for an authored scale**.
  The particle leaf names ARE the engine's `ModelParticle` names, so an emitted clone decodes
  natively. **A `TargetNodeName` naming a bone the mesh lacks is a SILENT no-op and deliberately not
  a finding**: bones live on the mesh and nothing server-side can enumerate them, so there is no
  honest check to make - it is documented at the class and in the authored `$Comment` instead.
- **`Limits.SpawnChance` and `Limits.ChanceFormula` are two knobs, not a mode.** The formula is a
  [`factor/FactorFormula`](../../../../../../../../../zc-core/src/main/java/com/ziggfreed/common/factor/CLAUDE.md)
  evaluated by `PlacementAnchors.resolveChance` (context: world + store + the placement id as
  PAYLOAD, **no subject** - nothing stands at the anchor yet, so a subject-dependent factor
  contributes 0 the way any value-side term does). Authored beside the scalar, the formula WINS and
  the scalar is reported as `CHANCE_FORMULA_AND_SCALAR`; an EMPTY formula group falls back to the
  scalar rather than reading as a constant 0. Only the chance VALUE changes - the deterministic
  `SplitMix64` keep/skip roll is untouched.
- **`Interact.Bindings` is a MAP keyed by channel, decoded through
  [`InheritMapCodec`](../../../../../../../../../zc-core/src/main/java/com/ziggfreed/common/codec/InheritMapCodec.java) - NOT an array.** An array leaf under
  `appendInherited` is a SINGLE leaf, so authoring it at all would drop every inherited entry and
  break per-binding `Parent` override, which is the flagship authoring shape. Each entry is
  [`PlacementBinding`](PlacementBinding.java) `{Param?,Value?,Values?,Amount?}`, every leaf
  independently optional and `appendInherited`. `Values` is the LIST payload for a channel that
  genuinely takes several strings, so no channel owner has to invent a separator inside `Value`
  (`effectiveValues()` is the null-safe read; entries arrive exactly as authored, since this library
  interprets nothing). Being one leaf, an authored `Values` replaces an inherited list whole.
  `NpcPlacementAssetCodecTest` proves the per-key merge (a child authoring one channel keeps the
  parent's others), because that is what the map buys.
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
  reads the NPC's own stamp, opens `Interact.Dialogue` **telling it who the conversation is with**,
  and fans `Interact.Bindings` out per namespace. Reading identity off the ENTITY is what lets one
  base role serve every placement in the server; a role cannot carry per-placement data, so an action
  that encoded a destination would need one role per destination.
  **The npc context is what makes a conversation NPC-aware**: without it a `MarkTalked` beat has
  nobody to credit, `@self` substitutes nothing, and every quest-aware condition asks about a
  character with no name and is answered no. The placement knows exactly who stands here, so it says
  so, and a conversation opened by pressing F behaves identically to the same one opened through a
  named route.
- **[`NpcRoleGenerator`](NpcRoleGenerator.java)** emits a NATIVE VARIANT per placement, not a role
  copy: `{"Type":"Variant","Reference":"<template id>","Modify":{...}}`, written into a
  `PackSource.RUNTIME` directory pack. `Identity.BaseRole` names a native PARAMETERIZED TEMPLATE
  ROLE shipped in any pack (a jar pack or a content pack) - this library ships none and knows none.
  The emitted `Modify` carries ONLY the authored knobs, under the seven key names the class exposes
  as constants and `modifyKeys()` lists: `Appearance`, `NameTranslationKey`, `Hint`, `Armor`,
  `Weapons` (hotbar), `OffHand`, `DefaultOffHandSlot`. Those names follow the vanilla humanoid
  templates (`Template_Intelligent` binds `HotbarItems: {Compute: "Weapons"}` and
  `OffHandItems: {Compute: "OffHand"}`), and a placement-backing template declares them in its own
  `Parameters` block.
  **A variant may only override a key its template DECLARED**: the engine refuses the whole variant
  over one undeclared key, which reads in game as an NPC that never appears. So generation asks
  [`RoleTemplates`](RoleTemplates.java) first and DROPS an undeclared key with a `severe` log naming
  the key and the template, keeping the rest of the NPC; the validator reports the same mismatch as
  `MODIFY_KEY_NOT_PARAMETERIZED`. Cross-pack `Reference` is safe by construction: a runtime pack is
  processed strictly after every boot-time pack, and role-to-model resolution happens by name at
  spawn rather than at pack load.
  **It emits MODELS as well as roles**, into `Server/Models/Zc_Gen_Mdl_<placementId>.json` of the
  SAME pack, whenever the appearance names a `Base` to clone; `Modify.Appearance` then points at
  that id, and a `Model`-form appearance points at the authored id with nothing written. Verified
  against `AssetRegistryLoader` (`:205,229-231`): the loader walks
  `assetPack.getRoot().resolve("Server").resolve(store.getPath())` for EVERY registered store, so
  `Server/Models/**` in a runtime directory pack is scanned exactly like `Server/NPC/Roles/**` - one
  `registerPack` covers both. Both output dirs are wiped and rebuilt per boot, so a removed
  placement leaves nothing stale. The pure cores `buildVariant(identity, placementId)`,
  `buildModify(identity, placementId)` and `buildModel(spec, placementId)` are what the tests pin.
  Call `generateAndRegister(version)` once per boot AFTER the asset fold and BEFORE any world
  streams chunks.
- **[`RoleTemplates`](RoleTemplates.java)** - the two READ-ONLY questions about a template, asked of
  the engine's own loaded roles through the shared `NPCPlugin.get().getBuilderManager()`:
  `templateExists(name)` (via `getRoleBuilderInfo`) and `unparameterizedKeys(name, keys)` (via
  `builder.getBuilderParameters().getParameterType(key) != VOID`, the exact check the engine itself
  performs when it parses a `Modify` block). Nothing is mutated, nothing is loaded, no event fires.
  **Every answer may be "cannot tell"** - `null` or an empty list - outside a running server or
  before a pack loads, and a caller then reports NOTHING, the same rule the model and particle id
  checks follow. Keys beginning with `_` are engine-reserved (`_CombatConfig`, `_InteractionVars`
  and friends) and are never called undeclared.
- **[`NpcPlacementValidator`](NpcPlacementValidator.java)** - the findings that are otherwise
  SILENT (an NPC that never appears is indistinguishable from one you have not walked to): blank
  role, an anchor group with no usable params, `SpawnChance <= 0` (suppressed when a working
  `ChanceFormula` is what is actually rolled against), an unregistered `Custom.Provider`,
  an unregistered `Requires.Factor` or `ChanceFormula` term, a `Where` naming no known selector (the
  SHARED `UNKNOWN_SELECTOR_NAME` from `WorldSelectorValidator`, so a placement and a dialogue
  condition report one code off one pool scan rather than two near-duplicates), an
  `ExcludeNames`-only selector, a colon-less `Interact.Bindings` key (`BINDING_KEY_NO_NAMESPACE`),
  an authored binding namespace no handler claimed (`UNCLAIMED_BINDING_NAMESPACE`), the appearance
  pair `APPEARANCE_MODEL_AND_BASE` (ERROR) / `APPEARANCE_OVERRIDE_WITHOUT_BASE` (WARN),
  `EMPTY_CHANCE_FORMULA` (WARN), `CHANCE_FORMULA_AND_SCALAR` (INFO - both authored still WORKS,
  since the formula is what is read, so it is a remark about clarity), and the best-effort id checks
  `UNKNOWN_APPEARANCE_MODEL` / `UNKNOWN_PARTICLE_SYSTEM` / `UNKNOWN_TEMPLATE` (WARN - the template
  may still load) / `MODIFY_KEY_NOT_PARAMETERIZED` (ERROR - two files that are both already here
  disagree) - those four read the engine's own loaded asset maps and role builders and answer
  "cannot tell" (reporting NOTHING) when nothing is up yet, which keeps them silent in a unit JVM
  and honest before assets fold. A generated role naming no `Identity.BaseRole` at all is
  `NO_BASE_ROLE` (ERROR). Reports shared
  [`validation.Finding`](../../../../../../../../../zc-core/src/main/java/com/ziggfreed/common/validation/CLAUDE.md)
  values under domain `placement` via `audit(placements)` / `audit(placement)`;
  `validateAll(Collection)` is the older `WorldSelectorValidator.Issue`-shaped view kept for a
  consumer that has not moved over yet (an `Issue` has no INFO-vs-WARNING distinction downstream, so
  a consumer still on it sees `CHANCE_FORMULA_AND_SCALAR` as a warning). `NpcPlacementConfig.audit()`
  audits the folded pool. Several checks read what is REGISTERED, so run the audit at
  first player-ready, not at plugin setup.

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
`NpcPlacementValidatorTest` covers the colon-less-key and unclaimed-namespace findings, the
appearance XOR table, and that the template checks stay SILENT with no engine to ask;
`AppearanceSpecTest` covers the group's round trip and its per-leaf `Parent` inheritance (including
"Particles is ONE leaf"); `NpcRoleGeneratorAppearanceTest` pins the EMITTED JSON field-for-field
against hand-written fixtures under `zc-dialogue/src/test/resources/npc/placement/` - a whole
expected variant, the exact key set of the model clone and of a particle entry,
`MinScale == MaxScale`, and the variant/model pairing on the generated id - plus the contract test
that every key the generator can emit is one the fixture TEMPLATE declares in its `Parameters`
block, which is the offline half of what `RoleTemplates` checks live; `RoleTemplatesTest` pins the
cannot-tell contract and proves the base-role indirection is GONE (the three classes no longer
load, the generator declares none of the retired methods); `PlacementChanceFormulaTest` pins
formula-over-scalar precedence, the empty-formula fallback, and the no-subject / degrade-to-zero
context; `PlacementRegistryTest`
covers fail-closed factors (including the bounds-less presence check and the placement-id payload),
no-position anchors and the namespace split;
`NpcPlacementAssetCodecTest` proves the per-key `Bindings` override. Every new CODEC is asserted in
[`AssetCodecInitTest`](../../../../../../../../../src/test/java/com/ziggfreed/common/asset/AssetCodecInitTest.java).
The engine-touching paths (spawn, sweep, pin, role generation) have no unit coverage and land behind
in-game smoke, matching the rest of the mod's split.
