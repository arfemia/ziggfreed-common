# npc/placement/ - the NPC placement engine

Router for `com.ziggfreed.common.npc.placement`. ONE generic engine for "put an NPC somewhere, make
press-F do something, and keep exactly one of it standing", with open registries so a pack author
and a fourth-party mod can both compose against it without Java. Content is authored at
`Server/ZiggfreedCommon/NpcPlacements/<id>.json`; common ships ZERO placement content.

## Layout

Five subpackages beside the pre-existing `admin/` + `command/`: [`asset/`](asset/CLAUDE.md) (the
asset, its config fold, the owner switch, authoring helpers and the validator),
[`registry/`](registry/CLAUDE.md) (the open registries and the gate chain),
[`anchor/`](anchor/CLAUDE.md) (where an NPC may stand: positions, the structure and zone indexes,
the marker system), [`runtime/`](runtime/CLAUDE.md) (the reconciler, the two authorities, the
service, pins, caches, diagnostics), and `interact/` (press-F:
[`ActionPlacementInteract`](interact/ActionPlacementInteract.java) + its
[builder](interact/BuilderActionPlacementInteract.java), registered `"ZigPlacementInteract"` by
[`PlacementNpcActions`](interact/PlacementNpcActions.java) - three files, described in full under
The runtime below). This file stays the engine's whole story; each subpackage router is the short
map of what lives there. The test package mirrors the split, each test beside its subject;
`RoleGenerationRetirementTest` stays at the package root, pinning what must never reappear there.
`admin/` is the placement admin SCREEN: [`NpcPlacementAdminPages`](admin/NpcPlacementAdminPages.java)
is the only way in, deliberately NOT a registered destination (so a pack can never point press-F at
it), and its [`NpcPlacementAdminDeps`](admin/NpcPlacementAdminDeps.java) audience seam defaults to
DENY because the library cannot know what an admin is on a given server. Its role picker (the
shared `ZigSearchRow` over `spawnableRoles`, capped at 12 rows of `ZigListRow.ui`) draws each
offered role with its portrait from `NpcPlacementAuthoring.roleIcon`, text-only on any miss.

## Read this first: the two authorities

> **NEVER place from absence alone.** A chunk unload REMOVES an entity from the store and restores
> it when the chunk ticks again, so a `forEachEntityParallel` sweep cannot tell "never placed" from
> "placed, chunk asleep". Placing on absence spawns a second NPC every time a player walks back
> into range. Placement requires `ledgerMiss && anchorChunkLoaded`. `Lifecycle.KeepAlive` masks the
> problem for one placement and for nothing else, so it is not a fix.

- **[`PlacedNpcComponent`](runtime/PlacedNpcComponent.java)** (`ZiggfreedCommon:PlacedNpc`) is the
  **despawn/orphan authority**: a sweep over it answers "what is standing that should not be"
  (placement deleted / gate denies / `Where` no longer matches). Registered once by
  `NpcBootstrap`, attached on the **pre-add `Holder`** (no live-ref race). Its pure
  snapshot is [`PlacedNpcIdentity`](runtime/PlacedNpcIdentity.java).
- **[`NpcPlacementLedger`](runtime/NpcPlacementLedger.java)** is the **place authority**: a persisted
  `(world | placementId | anchorKey) -> uuid` row that survives both the chunk sleeping and the
  server restarting. `mods/ziggfreedcommon/npc-placement-ledger.json`.

Neither can do the other's job, which is why there are two.

## The asset

- **[`NpcPlacementAsset`](asset/NpcPlacementAsset.java)** - Pattern A, **every leaf `appendInherited`**,
  so a `Parent`-linked child overriding one leaf keeps every untouched sibling. A `Parent` value
  must spell the target's EXACT filename (minus `.json`, case and all): same-pack parent resolution
  is case-sensitive in the engine, and a mismatched ref drops the child at load with a boot
  validation error. Groups: top-level
  `Enabled`; `Identity{Role,NpcId,Aliases}` (`Role` names a native NPC role, which owns everything
  about the character - see the next two bullets); `Where` (a
  [`WorldSelector`](../../../../../../../../../zc-world/src/main/java/com/ziggfreed/common/world/WorldSelector.java) - a null/empty `Where` defaults to
  `Match:["default"]` **at this read site**); `Anchor{WorldSpawn,Coords,Structure,Zone,Custom}`
  (nullable ORTHOGONAL groups, never a placement-mode enum); `Requires{Factors[]}`;
  `Limits{SpawnChance,ChanceFormula,MaxPerWorld,OncePerWorld}`; `Lifecycle{KeepAlive,Respawn,Fortify,
  FortifyHealth}` (ALL opt-in, default false - each costs a pinned chunk / a re-place / a health
  pool); `Interact{Dialogue,Open}`.
- **`Identity.NpcId` + `Identity.Aliases` are what CONTENT calls this character** - a quest's giver,
  a hand-in target, a talk objective, the waypoint marked for it. Both OPTIONAL: **with no `NpcId`
  the character IS its `Role`**, so putting an NPC somewhere is already enough to make it nameable,
  and two placements of one role are two standings of one character - a quest bound to it is
  offered, credited and handed in at either. Authoring an `NpcId` opts OUT of that and makes this
  standing a character nothing else answers to, which is how a step is scoped to one of several
  (the jar's temple guide is exactly that: its own id, plus the spawn guide's as an alias).
  Most placements therefore carry no `Identity` beyond `Role`. `Aliases` is one leaf, so an authored
  list replaces the parent's whole list rather than adding to it. Identity lives HERE rather than
  inside `Interact` because it is who the NPC IS, not what pressing F does - a placement with no
  `Interact` block still has a name. The resolution rules, the primary-versus-alias asymmetry and
  the reverse index are [`../NpcIdentities`](../CLAUDE.md).
- **`Identity.Role` is the WHOLE description of the character, and it is a hand-authored role file.**
  A role owns the look, the nameplate, the press-F prompt, the worn armour and the held items; this
  engine reads none of that and stores none of it, it only says which role to stand where. A
  character that should look or read differently gets its own role file, which is almost always a
  three-line native `Variant` of a shared template:
  `{"Type":"Variant","Reference":"<template>","Modify":{"Appearance":"<Model id>",
  "NameTranslationKey":"<key>"}}`. Two placements standing the same character in two worlds name the
  same role. Common ships NO roles and knows none.
  - **Why a placement cannot describe a character instead.** A native role builder resolves a
    `{"Compute":"<Param>"}` binding only on fields it reads through a Holder-typed reader, and
    which fields those are is a property of the reader the engine happens to have wired - not
    anything a server can inspect from outside. Two fields on the roles this engine stands up are
    read LITERALLY and so can never be supplied from elsewhere: a role's `Armor`
    (`BuilderRole` -> `expectStringArray`) and the `SetInteractable` action's `Hint`
    (`BuilderBase#checkForUnexpectedComputeObject`, "not computable (yet)"). Anything building a
    role from outside the file could therefore only ever cover part of a character, and would fail
    by producing an NPC that never appears with nothing on screen to explain it. **A character
    needing its own press-F prompt needs a full role body, not a variant** - that is the one thing
    `Modify` structurally cannot say.
  - **A `Modify` key must be one the referenced template DECLARED** in its own `Parameters` block,
    or the engine refuses the whole role (`BuilderModifier.createScope`, "Parameter X does not
    exist or is private"). The vanilla humanoid templates declare `Appearance`,
    `NameTranslationKey`, `Weapons` (hotbar), `OffHand` and `DefaultOffHandSlot`, and a
    placement-backing template should declare exactly that set: no more, because a declared
    parameter bound to nothing reads as an offered knob and throws when used, and no fewer.
  - **Scale and texture live on the MODEL, not the role.** `Appearance` names a Model asset;
    re-skinning or resizing a character is a Model file carrying `Parent` plus `Texture` and
    `MinScale == MaxScale` (equal, so the engine's spawn-time draw yields a constant it then
    persists per instance as `MdlScl`) - **never `EntityScaleComponent` for an authored scale**.
    Vanilla does exactly this: `Feran_Burrower` is `Parent: Feran` plus a texture.
- **`Limits.SpawnChance` and `Limits.ChanceFormula` are two knobs, not a mode.** The formula is a
  [`factor/FactorFormula`](../../../../../../../../../zc-core/src/main/java/com/ziggfreed/common/factor/CLAUDE.md)
  evaluated by `PlacementAnchors.resolveChance` (context: world + store + the placement id as
  PAYLOAD, **no subject** - nothing stands at the anchor yet, so a subject-dependent factor
  contributes 0 the way any value-side term does). Authored beside the scalar, the formula WINS and
  the scalar is reported as `CHANCE_FORMULA_AND_SCALAR`; an EMPTY formula group falls back to the
  scalar rather than reading as a constant 0. Only the chance VALUE changes - the deterministic
  `SplitMix64` keep/skip roll is untouched.
- **`Interact` is TWO spellings of ONE value: `Dialogue` and `Open`.** `Dialogue` is the terse form
  of the case almost every talking NPC wants and folds into the same
  [`ui/route/Destination`](../../../../../../../../../zc-presentation/src/main/java/com/ziggfreed/common/ui/route/Destination.java)
  the general `Open` leaf carries, so the two cannot drift; `Interact.destination()` is the one read
  both go through. Authoring BOTH is `INTERACT_BOTH_FORMS` (ERROR) and the explicit `Open` runs, so
  the behaviour is at least the one written out in full. A placement authoring neither opens that
  character's quest list.
  - **A destination is a WHOLE leaf under `Parent`**: a child authoring one replaces the inherited
    one rather than merging, because half of one type's fields under another type's discriminator is
    not a destination anybody meant. The two leaves inherit independently, so a child changing what
    its parent opens writes the SAME leaf the parent used - answering an inherited `Open` with the
    terse `Dialogue` spelling leaves the child carrying both.
  - **The destination vocabulary is OPEN and process-wide** (`ui/route/Destinations`, zc-presentation):
    a mod registers a `Type` with its codec, handler and optional audit hook at `setup()`, and an
    unknown `Type` FAILS THE READ naming the file, so a placement can never carry a button that
    silently does nothing. Common seeds `Dialogue` and `Quests` in
    [`../NpcDestinations`](../CLAUDE.md).
- **`Requires.Factors` is the shared [`factor/FactorCondition`](../../../../../../../../../zc-core/src/main/java/com/ziggfreed/common/factor/CLAUDE.md)**
  `{Factor,Param?,Min?,Max?}` - a condition asks a registered provider for a number. Same leaf, same
  keys, same meaning a
  dialogue `Factor` condition has, so authored JSON is unchanged. **Fails closed**: an unregistered
  factor cannot RESOLVE at all (never a zero), and a `FactorCondition` rejects an unresolvable value
  whatever its bounds say - including the bounds-less presence-check form, which is exactly the
  shape "only where this mod is installed" is written in.
- **[`NpcPlacementConfig`](asset/NpcPlacementConfig.java)** - the `defaults < pack < owner` fold. Every
  merge clears the sweep debounce + the position cache, so a reload lands on the next sweep, and
  logs the FILE-LOCAL findings only (the cross-asset half waits for `runLateAudit()` - see the
  validator below). Registered by [`../../asset/FrameworkAssetRegistrar`](../../../../../../../../../src/main/java/com/ziggfreed/common/asset/FrameworkAssetRegistrar.java)
  with NO load-order edge: a placement's `Where` carries its own patterns, so nothing has to fold
  before a placement can be read.

## The open registries (the third/fourth-party story)

Each is JVM-global, case-insensitive, last-write-wins, and warns ONCE per unknown id. **What press-F
OPENS is no longer one of them**: that is the shared destination vocabulary
(`ui/route/Destinations`), which any mod registers a typed screen into, so a placement's own
registries are about where an NPC stands and whether it stands at all.

- **[`PlacementRegistryLedger<T>`](registry/PlacementRegistryLedger.java)** is this engine's naming of the
  shared [`registry/RegistryLedger`](../../../../../../../../../zc-core/src/main/java/com/ziggfreed/common/registry/CLAUDE.md):
  a one-line subclass that fixes the `[placement]` log label, so an overwrite warning says which
  engine it came from. Every semantic lives in the parent (per id: a value, its owning mod name, a
  failure count and the latest failure message; `put` overwrite-warns ONCE per id by IDENTITY not
  equality, so a consumer re-running its own `setup()` with the SAME instance is silent; `info()`
  is the snapshot an admin listing command reads), and `RegistrationInfo` is INHERITED - a
  qualified `PlacementRegistryLedger.RegistrationInfo` resolves as before, while an `import`
  statement must name the declaring `RegistryLedger`. The registries below hold no map of
  their own.
- **[`PlacementFactorRegistry`](registry/PlacementFactorRegistry.java)** - the static facade over ONE shared
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
- **[`AnchorResolverRegistry`](registry/AnchorResolverRegistry.java)** - `register(providerId, resolver)` or
  `register(providerId, owner, resolver)`, `info()`, backing `Anchor.Custom{Provider,Params}`, so a
  fourth party adds an anchor with ZERO common changes. Returned positions are re-stamped `CUSTOM`
  with the provider id folded into the instance id (two providers can never collide). **A
  resolver's `instanceId` must be STABLE across restarts** - a bare loop index changes with
  ordering and mints a duplicate NPC.
- **[`PlacementGates`](registry/PlacementGates.java)** - the ordered veto chain over
  [`PlacementGate`](registry/PlacementGate.java) (`GateContext{placement, world, store}` (plus its derived `placementId()`) ->
  `GateVerdict{allowed, reasonKey}`). **Any deny wins and the FIRST deny is reported**, so ordering
  matters; the three built-ins are the asset's `Enabled`, the owner override, then the authored
  `Requires`. A throwing gate is skipped, not treated as a deny. **A deny DESPAWNS the standing
  NPC on the next sweep** - that is what makes an admin switch immediate.

## The runtime

- **[`NpcPlacementReconciler`](runtime/NpcPlacementReconciler.java)** - the correctness core. Two PURE
  decision cores (`decideResident` -> KEEP/DESPAWN/REBIND, `decidePlace` -> PLACE/REPLACE/SKIP) plus
  a three-pass live `sweep(world, store)`: **DESPAWN** (component-authoritative, frees a
  `MaxPerWorld` slot in the same pass, and MINTS a ledger row for a correct-but-unrecorded resident
  before the place pass can misread it as missing) -> **HEAL** (a ledger row whose entity lacks the
  stamp is adopted, not duplicated) -> **PLACE** (ledger-authoritative). An **in-flight claim set**
  keyed `(world, placementId, anchorKey)` guards two players entering a fresh instance in one tick
  (the first add is invisible until the command buffer flushes). A **per-world debounce latch**
  keeps a world entry from running a full parallel scan every time; it is cleared by an asset
  reload, a gate change, a new marker sighting, a zone discovery, and world removal. `requestSweep`
  is debounced, `forceSweep` is not; **both defer through `world.execute`**, because spawning an
  entity from inside a system throws and the throw becomes a silently missing NPC.

  **A ledger row EXISTING for `(world, placementId, anchorKey)` is not the same question as that row
  NAMING this resident entity's own uuid**, and the despawn pass asks the second one. Two entities
  can carry the identical stamp after an earlier REPLACE (the recorded one was briefly unresident
  when a sweep ran, so a new one was placed and the row re-pointed to it); the row still exists, it
  just names the OTHER entity now. Collapsing that into a mere existence check is the fixed
  regression: it read both entities as "matches" forever, so neither despawn pass ever pruned the
  surplus one and the world gained one more of it every time the recorded entity was briefly absent
  at sweep time. Despawning a surplus duplicate never calls `releaseInstance` (that would drop the
  ledger row, pin and cached position the SURVIVING entity still needs, under the very key they
  share) - only a despawn for a genuinely gone instance (unknown placement, denied gate, `Where` no
  longer matching) does.

  | resident | gate denied / placement gone / world no longer matches | -> DESPAWN (+ ledger/pin/position released) |
  |---|---|---|
  | resident | row exists, names a DIFFERENT entity (a surplus duplicate) | -> DESPAWN (ledger untouched - the row is correct) |
  | resident | correct, NO row exists at all | -> REBIND (adopt: mint a row naming this entity) |
  | resident | correct, row exists and names THIS entity | -> KEEP |
  | absent | **ledger hit + chunk ASLEEP** | -> **SKIP (the double-place regression)** |
  | absent | ledger miss + chunk ASLEEP | -> SKIP |
  | absent | ledger miss + chunk loaded + under capacity | -> PLACE |
  | absent | ledger hit + chunk loaded + entity gone + `Respawn` | -> REPLACE |

- **[`NpcPlacementService`](runtime/NpcPlacementService.java)** - thin policy over
  [`../NpcSpawnService`](../NpcSpawnService.java) (which gained an ADDITIVE `preAdd`+`postSpawn`
  overload for the no-race stamp attach): `place`/`despawn`/`releaseInstance`/`fortify`/`pinChunk`/
  `isChunkLoaded`. `fortify` raises max health enormously because a role's `Invulnerable` flag is
  NOT consulted by a direct stat-map health write, so a "true damage" effect can otherwise kill a
  service NPC and take every player's access to it with it.
- **[`PlacementKeepAlivePins`](runtime/PlacementKeepAlivePins.java)** - `addKeepLoaded` is REFERENCE
  COUNTED with no auto-release, so a sweep re-pinning a standing NPC would raise the count forever.
  Owns `world -> chunk -> Set<placementKey>`: **pin on FIRST insert, unpin on LAST removal**, whole
  world dropped by a `WorldEvictors` evictor (which is also what stops an instance teardown leaking
  pins). `applyClaim` is the PURE edge core.
- **[`PlacementAnchors`](runtime/PlacementAnchors.java)** - the union/limits engine. Several groups produce
  the UNION, each an independent instance keyed `(kind, instanceId)`; `MaxPerWorld` counts ACROSS the
  union; `OncePerWorld` collapses to the first in DECLARATION order (WorldSpawn, Coords, Structure,
  Zone, Custom) so the survivor is readable off the file, not dependent on chunk-load timing;
  `SpawnChance` is a DETERMINISTIC `SplitMix64` roll over `(worldSeed, placementId, anchorKey)`,
  never `java.util.Random`.
- **[`AnchorPosition`](anchor/AnchorPosition.java)** - `(kind, instanceId, x, y, z, yaw)`; `anchorKey()` is
  a PERSISTED format (it is a ledger key component), so changing it orphans every row.
- **[`StructureAnchorIndex`](anchor/StructureAnchorIndex.java)** + **[`PlacementMarkerSystem`](anchor/PlacementMarkerSystem.java)**
  + **[`StructureMarkerSightings`](anchor/StructureMarkerSightings.java)** - the structure driver. A marker
  is only knowable when its chunk loads, so the system records sightings into the live index (what
  anchors read) and the bounded ring buffer (what an author reads to discover real marker ids), then
  clears the debounce and asks for a sweep. **Keyed by the marker's FLOORED world position, and
  the sighting query is `SpawnMarkerEntity` ALONE.** A marker entity reaches a store two ways and
  only its position is stable across both: block-synthesized fresh (new uuid, WITH a
  `SpawnMarkerBlockReference`) as an open-world chunk loads, or loaded directly from an instance
  world's saved chunks carrying NO block reference and NO `FromWorldGen`/`FromPrefabInstance` at
  all (live-scanned in the Forgotten Temple: every marker reads `blockRef=NO
  fromPrefabInstance=NO`; shared-source evidence in the hytale-source-search ledger under
  "spawn-marker provenance"). Requiring ANY second component silently excludes one of the two
  paths; the floored position is identical for both, since a synthesized marker stands centered on
  its block. `/mmonpc list markers` scans the LIVE store (the ground truth) beside `list
  structures` (what got recorded). The index is transient by design: an unknown marker and an
  unloaded one lead to the same correct decision to do nothing.
- **[`ZoneAnchorIndex`](anchor/ZoneAnchorIndex.java)** - `notifyZoneDiscovered(world, store, zoneName,
  regionName, x, y, z)`. **The engine owns the anchor; the consumer supplies the trigger** (only a
  consumer knows what a zone is and what counts as discovering one). A discovery kicks a sweep.
- **[`NpcPlacementPositionCache`](runtime/NpcPlacementPositionCache.java)** - keyed `(worldName,
  placementId, anchorKey)`, **never by placement id alone**: two concurrent instances of one dungeon
  share a placement id, and a single-key cache would point a player in instance A at instance B. NOT
  an authority - it exists so a quest-waypoint feature can point at an NPC whose chunk is asleep.
- **[`NpcPlacementOverrides`](asset/NpcPlacementOverrides.java)** - the owner switch at
  `mods/ziggfreedcommon/npc-placements.json`, `{"<key>": {"enabled": false}}`. **One key grammar,
  no nested sections**: a placement id, a trailing-`*` prefix (which IS the per-mod section), or the
  bare `*`. Most specific wins (exact > longest prefix > `*`), so `{"*":{"enabled":false},
  "mmo_hub":{"enabled":true}}` leaves exactly the hub standing. Writes through
  [`../../util/JsonOverrideWriter`](../../../../../../../../../zc-core/src/main/java/com/ziggfreed/common/util/JsonOverrideWriter.java) (atomic, `$Comment` and
  siblings preserved); a malformed file is never overwritten. **A caller must force a sweep after
  writing** or the switch waits for the next restart.
- **[`ActionPlacementInteract`](interact/ActionPlacementInteract.java)** + its
  [builder](interact/BuilderActionPlacementInteract.java), registered `"ZigPlacementInteract"` by
  [`PlacementNpcActions`](interact/PlacementNpcActions.java) - ONE press-F action for every placement. It
  reads the NPC's own stamp, resolves the placement's `Interact.destination()` (the `Dialogue` alias
  or the explicit `Open`, falling back to the character's quest list when the placement authors
  neither), and dispatches it through `ui/route/Destinations`. Reading identity off the ENTITY is
  what lets one base role serve every placement in the server; a role cannot carry PER-PLACEMENT
  data, so an action told its destination only in the role file would need one role per placement.
  **An NPC with NO stamp is not a dead end**: the stamp is attached only by the sweep, so one spawned
  by a command, an egg, a prefab or another mod carries none, and a press-F that found none used to
  render its prompt and then silently do nothing. The builder therefore reads two OPTIONAL role-level
  fields, `Dialogue` (a `StringHolder`, so a native `Variant` can bind it per character through its
  template's own `Parameters`) and `Open` (raw JSON, decoded on FIRST PRESS-F rather than at role
  load, since decoding marks the destination vocabulary as read and a role asset is read while mods
  are still registering theirs; not computable, because a `Compute` object is not a destination).
  They are a FALLBACK, never an override - a placement standing this NPC still decides, so one shared
  role can be re-pointed per standing - and with no placement the identity comes from
  `NpcIdentities.npcIdOfEntity` instead, so `@self` and a `MarkTalked` beat still resolve.
  **The identity travels with every open**, whatever the destination turns out to be: without it a
  `MarkTalked` beat has nobody to credit, `@self` substitutes nothing, and every quest-aware
  condition asks about a character with no name and is answered no. The placement knows exactly who
  stands here, so it says so in the `DestinationContext` (npc ref, npc id, placement id, plus the
  role's dialogue-deps key), and pressing F behaves identically to opening the same destination from
  anywhere else.
- **[`NpcPlacementService.roleFor`](runtime/NpcPlacementService.java)** is the whole role resolution: the
  authored `Identity.Role`, trimmed, or `null`. There is no fallback that invents one, so a
  placement naming no role stands nothing up and the validator says so as `NO_ROLE`.
- **[`NpcPlacementValidator`](asset/NpcPlacementValidator.java)** - the findings that are otherwise
  SILENT (an NPC that never appears is indistinguishable from one you have not walked to): no
  `Identity` at all (`NO_IDENTITY`) or one naming no role (`NO_ROLE`), an anchor group with no
  usable params, `SpawnChance <= 0` (suppressed when a working
  `ChanceFormula` is what is actually rolled against), an unregistered `Custom.Provider`,
  an unregistered `Requires.Factor` or `ChanceFormula` term, a `Where` describing none of the loaded
  worlds (the SHARED `MATCHES_NO_LOADED_WORLD` from `WhereValidator`, so a placement and a dialogue
  condition report one code off one audit rather than two near-duplicates), an
  `ExcludeMatch`-only `Where` (`EXCLUDE_ONLY`), both `Interact` spellings at once
  (`INTERACT_BOTH_FORMS`, ERROR),
  a press-F opening a conversation no loaded file carries (`UNKNOWN_DIALOGUE`, WARN - read through
  `Interact.destination()`, so the terse `Dialogue` leaf and an explicit `Open` of that type are the
  same check), a character whose role resolves no `NameTranslationKey` through
  [`../NpcNames`](../CLAUDE.md) and so shows no name anywhere (`NO_DISPLAY_NAME`, WARN),
  `EMPTY_CHANCE_FORMULA` (WARN), and `CHANCE_FORMULA_AND_SCALAR` (INFO - both authored still WORKS,
  since the formula is what is read, so it is a remark about clarity). The destination's OWN audit
  runs in the cross-asset half (`Destinations.validate`), so a type's params are checked by the mod
  that registered it, under that mod's domain, rather than by a check here that could only guess.
  **Whether the named ROLE exists is not checked BY THIS AUDIT, and at audit time cannot be**: role
  parsing is a bespoke builder framework rather than an asset store, and asking the shared
  `BuilderManager` about a role is either a lie before the packs load or a live-state mutation
  afterwards. A misspelled role id shows up as the boot log's own role-load error.
  **At LIVE time the question is perfectly answerable, and is not the same question.** Once the
  server is up, `NPCPlugin.hasRoleName` answers whether a role exists and `validateSpawnableRole`
  additionally refuses an ABSTRACT one, which is a placement that can never appear; the first-party
  entity tool's spawn page reads the same registry through `getRoleTemplateNames(true)` to list what
  it may spawn. `NpcPlacementAuthoring.isSpawnable` / `spawnableRoles` wrap both for the command and
  the admin page, and both answer permissively when there is no registry to ask, so they stay a
  courtesy check at the point somebody TYPES a role rather than a second gate the engine consults.
  `NpcPlacementAuthoring.roleIcon` sits beside them for the admin page's picker rows (the role's
  spawn model, then that model's own `Icon`, each hop guarded; null means the row reads as its name
  alone, painted through `ui/icon/IconRenderer` into `ZigListRow.ui`'s `#IconSlot`). Reports shared
  [`validation.Finding`](../../../../../../../../../zc-core/src/main/java/com/ziggfreed/common/validation/CLAUDE.md)
  values under domain `placement`.
  **TWO entry points, because the checks answer two kinds of question, and asking the second kind
  early is how a boot invents a finding.** `auditFileLocal(placements)` / `auditFileLocal(placement)`
  is the FILE-LOCAL half: shape, spelling and self-contradiction, all answerable from the file
  alone, so it holds however much of the server is up. `audit(placements)` / `audit(placement)` is
  that plus the CROSS-ASSET half - `MATCHES_NO_LOADED_WORLD`, `UNREGISTERED_FACTOR`,
  `UNREGISTERED_ANCHOR_PROVIDER`, `UNKNOWN_DIALOGUE`, `NO_DISPLAY_NAME`, and the destination's own
  per-type audit - each of which asks an open registry, another store or the running universe
  whether something exists. **Each of them stays SILENT when its source is not up rather than
  guessing**: no loaded conversation means "cannot tell", not "no file carries it", and the same for
  a role registry that has not loaded. Those answers
  are only trustworthy once every store has folded, every mod's `setup()` has run and the universe
  is up, so the full audit belongs at first player-ready, never at a layer fold or at plugin setup.
- **The two moments on [`NpcPlacementConfig`](asset/NpcPlacementConfig.java)**: every layer merge calls
  `logFindings()`, which logs `auditFileLocal()` only; `runLateAudit()` runs and logs the full
  `audit()` ONCE per boot, driven from the first `PlayerReadyEvent` by `NpcBootstrap`. A
  consumer that folds these placements into its own content report calls
  `claimLateAudit("<mod>")` at its `setup()` and common's own late audit stands down with one line
  naming it, so the same findings are reported once rather than twice. `lateAuditOwner()` is who
  holds the claim.

## Wiring (what `NpcBootstrap` owns, called from the root's `setup()`)

`PlacedNpcComponent.register(...)`, `PlacementNpcActions.register()`, the `PlacementMarkerSystem`,
the overrides + ledger load, the `/zignpc` admin family ([`ZigNpcCommand`](command/ZigNpcCommand.java),
one verb `place`; nodes `ziggfreed.ziggfreedcommon.command.zignpc[.<verb>]`, so a consumer wanting
`/mmonpc` registers an alias rather than a second implementation), the first-`PlayerReadyEvent`
listener driving `NpcPlacementConfig.runLateAudit()`, **and common's own
`AddWorldEvent`/`RemoveWorldEvent` listeners**.
That last one is not tidiness: eviction used to be driven only from CONSUMER listeners, so two
consumer mods fired the `WorldEvictors` fan-out twice per world - harmless for a `map::remove`,
corrupting for a refcounted unpin. `WorldEvictors.onWorldRemoved` also gained an idempotence guard
(bounded, keyed by world NAME so a removed world object is not held alive), cleared by
`onWorldAdded`.

## Tests

Pure decision cores only, never balance numbers. `NpcPlacementReconcilerTest` leads with the named
double-place regression, and separately pins the stacking regression (a row that EXISTS but names a
different entity despawns rather than being read as a match, and never as an adoption candidate);
`PlacementGateChainTest` covers any-deny-wins + first-deny-reported +
override precedence; `PlacementKeepAlivePinsTest` covers the pin/unpin edges; `PlacementAnchorsTest`
covers union / collapse order / cross-union capacity / roll determinism; `PlacementRegistryLedgerTest`
covers identity-vs-equality overwrite warnings, failure counting and the `info()` snapshot through
the subclass (the base contract itself is `zc-core`'s `RegistryLedgerTest`);
`NpcPlacementValidatorTest` covers the both-forms Interact error plus the identity that names no
role;
`NpcPlacementAuditScopeTest` (in `registry/`, beside the registries it clears) pins the fold-time /
late split - no cross-asset code from
`auditFileLocal`, the file-local ones still reported there, all of them back in `audit`, the
not-yet-registered factor that used to produce a phantom finding mid-fold, and the two checks whose
SOURCE can be absent (the conversation store and the role registry) staying silent rather than
naming every placement at once; `NpcPlacementConfigAuditTest` (in `asset/`, beside the config whose
test hooks it needs) pins the config's two moments - the fold logging only the file-local half, the
late audit's run-once, and the claimed stand-down;
`RoleGenerationRetirementTest` pins that a placement has exactly one way to say who stands there -
`Identity` carries a role id, a character id and its aliases and nothing describing a look, a name
or a prompt, and `roleFor` returns the authored id or nothing rather than inventing one;
`PlacementChanceFormulaTest` pins
formula-over-scalar precedence, the empty-formula fallback, and the no-subject / degrade-to-zero
context; `PlacementRegistryTest`
covers fail-closed factors (including the bounds-less presence check and the placement-id payload)
and no-position anchors;
`NpcPlacementAssetCodecTest` proves that the two `Interact` spellings fold to one value, that both
at once is visible, and that a destination inherits as a whole leaf (zc-presentation's
`DestinationsTest` owns the vocabulary's own decode / dispatch / audit contract). Every new CODEC is asserted in
[`AssetCodecInitTest`](../../../../../../../../../src/test/java/com/ziggfreed/common/asset/AssetCodecInitTest.java).
The engine-touching paths (spawn, sweep, pin) have no unit coverage and land behind
in-game smoke, matching the rest of the mod's split.
Whether the ROLE files a consumer ships are valid variants of the templates they reference is the
consumer's own test to write, since common ships no roles: the MMO jar's
`MmoRoleTemplateParameterTest` is the model, walking its own roles plus the content packs' and
asserting every `Modify` key is one the referenced template declares.
