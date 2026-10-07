# npc/placement/

**Never place from absence alone.** A chunk unload removes an entity from the store, so a sweep cannot tell "never placed" from "placed, chunk asleep": placing needs a ledger miss AND a ticking anchor chunk section (on Update 7 a section sleeps whatever its column does). `Lifecycle.KeepAlive` is not a fix.

## Runtime

- Two authorities: `PlacedNpcComponent` on a resident decides despawn, and `NpcPlacementLedger` (persisted `(world|placementId|anchorKey) -> uuid` rows in `mods/ziggfreedcommon/npc-placement-ledger.json`) decides place.
- The component id `ZiggfreedCommon:PlacedNpc` and `AnchorPosition.anchorKey()` are persisted formats: never rename or reshape them. A custom anchor resolver's `instanceId` must be stable across restarts, or each restart mints a duplicate.
- A world's removal is not its deletion (M133): the engine removes every world at a server stop (as `EXCEPTIONAL`, the reason a crash carries), and `/world remove` keeps the folder. `NpcPlacementReconciler.onWorldRemoved` clears sweep state on any removal, but drops the world's ledger rows and cached positions only when its config's `DeleteOnRemove` or `DeleteOnUniverseStart` says it is deleted (`isDeleted`). Dropping them at a stop made each boot re-adopt every NPC and brought back a killed NPC whose placement has no `Respawn`.
- The despawn pass asks whether the ledger row names THIS entity, not whether a row exists. A surplus duplicate despawns without `releaseInstance`, which would drop the survivor's row, pin and cached position.
- Every sweep defers through `world.execute`: spawning inside a system throws, and the throw becomes a silently missing NPC.
- The place pass reads the anchor's chunk SECTION (zc-world's `world/TickingSections.stateAt`), never the column's `ChunkFlag.TICKING`: an NPC spawned into a sleeping section is parked with no ledger row and no `Fortify`, and every later pass would park another. A sleeping section in memory is woken on the spot and placed into on the sweep's second round (`settleRounds`), never in the round that woke it (`AnchorSections`), so the despawn pass first adopts what the wake brought back, one copy per instance (`planAdoptions`; the rest are removed). A section not in memory is requested ticking (`requestAnchorSection`), and only a landed request sweeps again. `NpcPlacementService.place` logs one INFO line per NPC placed. Before any spawn the place pass checks for a copy of the instance by its `PlacedNpc` stamp, among the copies the despawn pass kept or adopted and the holders parked or saved in the anchor's section (`spawnsThisRound`, `NpcPlacementService.heldInstances`); a found copy means no spawn. Every copy the despawn pass keeps or adopts, never a surplus one, gets its upkeep (`NpcPlacementService.upkeep`: cached position, keep-alive pin, `Fortify`).
- A `WorldSpawn` anchor reads its point through `runtime/WorldSpawnPoints`: one query per world stays on its way across passes, a pass that finds it still loading resolves nothing (the retry signal), and its landing with a point forces a sweep, so the anchor places even after the retry budget is spent. A failed query logs one WARNING per world until a point lands, and wakes nothing.
- `PlacementKeepAlivePins` is reference counted: pin on the first insert, unpin on the last removal, never re-pin per sweep. Both read the column's residency (`NpcPlacementService.residentChunk`), never its ticking flag: a held column stops ticking once its timer runs out, and an unpin skipped then leaks the count.
- `NpcPlacementPositionCache` is keyed `(world, placementId, anchorKey)`, never by placement id alone (two instances of one dungeon share it), and is never an authority.
- `fortify` raises max health because a direct stat-map health write ignores a role's `Invulnerable` flag. It is applied at the add by `runtime/PlacementFortifySystem` (a holder system after the engine's stat setup and NPC balancing, on a spawn and a load alike), never in a post-spawn, which the engine skips for an NPC it parks; it fills the pool only on a spawn or when the bonus was missing.
- Query structure markers as `SpawnMarkerEntity` alone, keyed by floored world position: an open-world marker is re-created by its block with no `From*` tag, and an instance's saved marker has no `SpawnMarkerBlockReference` (`hytale-shared-source/HytaleServer/NPC/src/main/java/com/hypixel/hytale/server/spawning/blockstates/SpawnMarkerBlockStateSystems.java`).
- A calendar event's start and end force a sweep of every live world (zc-objectives `objectives/calendar/CalendarPlacementSweep`). Any other moment that changes what a `Requires` answers (a feature a mod flips at runtime) calls `forceSweep` itself, or the change waits for the world's next ordinary sweep.

## Authoring

- `Identity.Role` names a hand-authored native role, which owns the look, nameplate, press-F prompt, armour and held items; this engine stores none of them. A per-character role is usually a native `Variant` of a template, and each `Modify` key must be declared in that template's `Parameters` or the engine refuses the role.
- A role's `Armor` and a `SetInteractable` `Hint` are read literally (no `Compute` binding), so a character with its own press-F prompt needs a full role body, not a variant.
- Scale and texture live on the Model asset (`Parent` plus `Texture`, `MinScale` equal to `MaxScale`), never on `EntityScaleComponent`.
- A `Parent` value must spell the target file's exact name, case included, or the engine drops the child at load.
- `Limits.SpawnChance` and `Limits.ChanceFormula` are two knobs: the formula wins when both are authored, an empty formula falls back to the scalar, and the roll is a deterministic `SplitMix64` over `(worldSeed, placementId, anchorKey)`.
- `NpcPlacementAuthoring.place` is the one writer behind `/zignpc place` and any consumer alias (owner-file write, owner-layer re-read, forced sweep); never add a second.
- After writing the owner switch file `mods/ziggfreedcommon/npc-placements.json`, call `NpcPlacementReconciler.forceSweep`, or the change waits for the next restart.

## Audit

- `auditFileLocal` runs at every fold. The full cross-asset audit runs once, at the first `PlayerReadyEvent`, because asking earlier invents findings; a consumer that folds placements into its own report calls `claimLateAudit`.
- The audit cannot check that a named role exists (roles are not an asset store); `NpcPlacementAuthoring.isSpawnable` is a courtesy check where someone types a role.
- `NpcPlacementConfig.rolesByPlacement()` feeds zc-encounter's `EncounterAudit` through the wiring root, so a placement role that is an encounter script id is reported under the encounter domain.
