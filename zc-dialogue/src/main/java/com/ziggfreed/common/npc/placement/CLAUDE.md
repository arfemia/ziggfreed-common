# npc/placement/

**Never place from absence alone.** A chunk unload removes an entity from the store, so a sweep cannot tell "never placed" from "placed, chunk asleep": placing needs a ledger miss AND a loaded anchor chunk. `Lifecycle.KeepAlive` is not a fix.

## Runtime

- Two authorities: `PlacedNpcComponent` on a resident decides despawn, and `NpcPlacementLedger` (persisted `(world|placementId|anchorKey) -> uuid` rows in `mods/ziggfreedcommon/npc-placement-ledger.json`) decides place.
- The component id `ZiggfreedCommon:PlacedNpc` and `AnchorPosition.anchorKey()` are persisted formats: never rename or reshape them. A custom anchor resolver's `instanceId` must be stable across restarts, or each restart mints a duplicate.
- The despawn pass asks whether the ledger row names THIS entity, not whether a row exists. A surplus duplicate despawns without `releaseInstance`, which would drop the survivor's row, pin and cached position.
- Every sweep defers through `world.execute`: spawning inside a system throws, and the throw becomes a silently missing NPC.
- `PlacementKeepAlivePins` is reference counted: pin on the first insert, unpin on the last removal, never re-pin per sweep.
- `NpcPlacementPositionCache` is keyed `(world, placementId, anchorKey)`, never by placement id alone (two instances of one dungeon share it), and is never an authority.
- `fortify` raises max health because a direct stat-map health write ignores a role's `Invulnerable` flag.
- Query structure markers as `SpawnMarkerEntity` alone, keyed by floored world position: an open-world marker is re-created by its block with no `From*` tag, and an instance's saved marker has no `SpawnMarkerBlockReference` (`reference/shared-source/release/HytaleServer/NPC/src/main/java/com/hypixel/hytale/server/spawning/blockstates/SpawnMarkerBlockStateSystems.java`).
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
