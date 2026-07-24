# entity/performer/ - the station-performer seam (one contract, two backends)

Router for `com.ziggfreed.common.entity.performer`. The ONE internal seam a station-puppet
controller drives to present a "double performing the work", so it never branches on the look
source. Both backends implement `StationPerformer`; the caller supplies POLICY (which source, which
anchors, which prop), the backend owns MECHANISM. Design authority:
`../../../../../../../.claude/research/raw/rpg-stations-look-source-performer-seam-2026-07-24.md`
(the contract section) + `rpg-stations-npc-performer-feasibility-2026-07-24.md` (the NPC recipes,
sections 4b-4d) + decisions 46-54 in `rpg-stations-extraction-design.md`.

## The contract

- **[`StationPerformer`](StationPerformer.java)** - the seven-method seam:
  `spawn(PerformerSpawnCtx)` / `despawn()` / `presentAt(pos, yaw)` /
  `walkTo(target, speedMps) -> WalkHandle` / `setProp(PropSpec)` / `playClip(ClipSpec)` /
  `isAlive()` + `ref()`. A performer is STATEFUL: it captures the spawn `world`/`store` and its own
  spawned ref, reusing them for every later WORLD-THREAD call (all try-guarded to a no-op).
  **Hide is deliberately OFF this interface** - hiding the real player acts on the PLAYER (via
  `PlayerPuppetService.hideByScale`) and stays caller-owned regardless of backend. The caller calls
  `setProp`/`playClip` UNCONDITIONALLY; each backend decides what it can do (the NPC backend's
  prop/clip are best-effort until in-game-proven) without the caller knowing which backend it holds.
- **[`WalkHandle`](WalkHandle.java)** - a poll-driven handle over one walk (`poll(dtMs) -> State`,
  `isDone()`, `cancel()`); `State` = WALKING/ARRIVED/STUCK/FAILED. The caller owns the cadence (one
  `poll` per tick). Arrival/stuck semantics are encapsulated per backend. A never-started walk
  yields the singleton `FailedWalkHandle`.

## The two backends

- **[`HolderPerformer`](HolderPerformer.java)** - the bare-`Holder` skinned puppet, the shipped
  in-game-crowned route re-expressed behind the seam (byte-parity: it drives
  `PlayerPuppetService` + `PuppetNav`, owning only the STATE + the identity attach). `PlayerClone`
  clones the live skin; `Model` overlays a fixed authored model via `PlayerModelService.apply`
  (resolution ladder `modelId -> fallbackModelId -> leave the clone`, never a red-X). Walk = the
  bounded-A* `PuppetNav.solve` polyline advanced by `PlayerPuppetService.walkTick`. `spawn` threads
  `ctx.accessor()` (a `Store` or a `CommandBuffer`) into `PlayerPuppetService.spawn` so a lock-held
  engage-time caller spawns the puppet tick-safely; the captured `store` (nullable) backs the later
  methods and no-ops when a lock-held caller left it unset.
- **[`NpcRolePerformer`](NpcRolePerformer.java)** - the Role-driven `NPCEntity`, the spike-proven
  mechanism promoted to a backend (native `MotionControllerWalk` gait + engine A*). `spawn` =
  `NPCPlugin.spawnEntity` with the cloned-skin model (or role default), `preAddToWorld` attaches the
  identity (+ `NonSerialized` when not persisting), `postSpawn` re-applies the skin clone + SEEDS the
  leash. `spawnEntity` needs a concrete unlocked `Store`: it uses `ctx.store()` when a caller
  provided one (synchronous), else DEFERS the whole spawn to `world.execute` (a lock-held caller had
  only a `CommandBuffer`) - one-tick latency, `isAlive()`/`ref()` honestly null during the window. `walkTo` spawns an invisible marker, `Role.setMarkedTarget("MoveTarget", ref)`, re-anchors
  the leash, and handles the `getRole()`-null-until-first-tick race by retrying the bind on each poll.
  `setProp` = `InventoryHelper.useItem` (NPC-native hotbar; UNPROVEN render). `playClip` =
  `AnimationUtils.playAnimation` DIRECT (NEVER `NPCEntity.playAnimation`, the Emote-gate landmine;
  UNPROVEN gait coexistence). Prop/clip/live-re-anchor are best-effort until the maintainer smoke.

## Config + identity + persistence

- **[`PerformerLook`](PerformerLook.java)** - the resolved appearance/behaviour config (orthogonal
  knobs, no modes): `source` (PLAYER_CLONE/MODEL/NPC_ROLE), `modelId`/`fallbackModelId`, `roleId`,
  `skinSource` (PLAYER_CLONE/ROLE_DEFAULT), `speedMps`, `persist`. `kind()` routes to the backend.
- **[`PerformerSpawnCtx`](PerformerSpawnCtx.java)** - the one-shot spawn descriptor: live handles +
  config (owner ref/uuid, station key, position/yaw, look, optional initial prop/clip). The spawn
  handles are the ACCESSOR-FLEX seam (fixed the M2 crowned-puppet regression): `accessor`
  (`ComponentAccessor<EntityStore>`, REQUIRED - a `Store` OR a `CommandBuffer`, the seam
  `PlayerPuppetService.spawn` accepts) is what the Holder threads into spawn so a lock-held caller
  (inside `toggle()`/the heartbeat drain) spawns the puppet tick-safely, byte-parity with the
  shipped `StationPuppetController`; `store` (`@Nullable Store`) is a SEPARATE concrete live store
  an UNLOCKED caller MAY also provide for the NPC backend's `NPCPlugin.spawnEntity` (which does not
  take a `CommandBuffer`) - when absent, the NPC backend defers its spawn. `world` stays nullable
  (Holder walk path-solve + the NPC deferred-spawn hop). Builder `.liveStore(store)` sets BOTH
  accessor and store from one unlocked store (the boot/command-time shape).
  - **What the wiring leg passes per context**: *engage-time* (locked `toggle()`/heartbeat frame) =
    `.accessor(commandBuffer)` only, `.world(world)` for the NPC (Holder spawns via the
    CommandBuffer; NPC defers via `world.execute`, one-tick latency); *boot-time* (reconcile /
    plugin `setup()` via `world.execute`, unlocked) = `.liveStore(store)` (both backends
    synchronous); *command-time* (an unlocked command with a live store) = `.liveStore(store)`.
    Later-frame Holder MUTATIONS (drive/despawn) from inside a subsequent processing lock still need
    a per-frame accessor the wiring caller threads - NOT resolved by this ctx (it is spawn-scoped).
- **[`PropSpec`](PropSpec.java)** / **[`ClipSpec`](ClipSpec.java)** - value inputs. `PropSpec` = one
  item id or empty hands. `ClipSpec` = slot + optional item-animation set + clip id + sendToSelf
  (the full `AnimationUtils.playAnimation` knob set, so the Holder swing stays byte-parity and the
  NPC clip uses the SAME direct route).
- **[`PerformerKind`](PerformerKind.java)** - HOLDER (`"BareHolder"`) / NPC_ROLE (`"NpcRole"`); the
  stable `code()` is what the identity component stores.
- **[`PerformerIdentityComponent`](PerformerIdentityComponent.java)** - a REGISTERED ECS component
  (`OwnerUuid`/`StationKey`/`Kind`/`SpawnedAtMs`, PascalCase codec keys) both backends attach in
  `preAddToWorld`. A library component has no owning plugin, so a CONSUMER registers it ONCE at
  `setup()` via `register(getEntityStoreRegistry())` (sets `TYPE`); every attach/query guards on
  `TYPE != null`, so a performer still works un-registered (just no reconcile). `toIdentity()` is
  the pure [`PerformerIdentity`](PerformerIdentity.java) snapshot the decision core runs on.
  `persist=false` (default) marks `NonSerialized` (transient, matches every shipped puppet);
  `persist=true` is the reserved persistent-performer seam.
- **[`PerformerReconciler`](PerformerReconciler.java)** - the orphan-reconcile sweep. `sweep(store,
  policy) -> ReconcileSummary` queries every performer natively (`forEachEntityParallel` over the
  identity component), applies a pure `ReconcilePolicy` (KEEP/DESPAWN/REBIND), despawns the rejects
  via the iteration's command buffer, and hands REBIND performers back to the caller. Pure policy
  factories (unit-tested): `bootDespawnAll()` (no session survives a restart), `ownerNotLive(pred)`,
  `engageStale(engagingOwner, stationKey)` (despawn a stale double at the engaged block owned by
  someone else).
- **[`PerformerWalkMath`](PerformerWalkMath.java)** - pure arrival/stuck cores for the NPC walk
  (`horizontalDistance`, `arrived`, `nearMiss`, `stuck` + the tuned default thresholds).

## Threading + test split

WORLD-THREAD ONLY for every backend method; all engine calls try-guarded to a no-op, never a throw.
Tests cover the PURE cores + fakes only (identity codec round-trip, reconcile policy decisions, walk
math, value semantics, the `WalkHandle`/`StationPerformer` contract via fakes); the engine-touching
spawn/walk/prop/clip paths have no unit coverage (they need live component types) and land behind the
maintainer in-game smoke, matching the rest of the `entity/` package's split.

## `PlayerPuppetService` additions this leg

Two additive knobs on `PlayerPuppetService` (`../PlayerPuppetService.java`) back `HolderPerformer`,
byte-identical for existing callers: `PuppetSpawnRequest.persist(boolean)` (default false gates the
`NonSerialized` mark) and a `spawn(accessor, req, Consumer<Holder> preAdd)` overload (the
no-live-ref-race attach point for the identity component).
