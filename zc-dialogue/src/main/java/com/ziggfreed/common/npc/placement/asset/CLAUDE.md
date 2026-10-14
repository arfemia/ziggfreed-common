# npc/placement/asset/ - placement content, its one writer and its audit

## Authoring

- `Identity.Role` names a hand-authored native role, which owns the look, nameplate, press-F prompt, armour and held items; this engine stores none of them. A per-character role is usually a native `Variant` of a template, and each `Modify` key must be declared in that template's `Parameters` or the engine refuses the role.
- A role's `Armor` and a `SetInteractable` `Hint` are read literally (no `Compute` binding), so a character with its own press-F prompt needs a full role body, not a variant.
- Scale and texture live on the Model asset (`Parent` plus `Texture`, `MinScale` equal to `MaxScale`), never on `EntityScaleComponent`.
- A `Parent` value must spell the target file's exact name, case included, or the engine drops the child at load.
- `Limits.SpawnChance` and `Limits.ChanceFormula` are two knobs: the formula wins when both are authored, an empty formula falls back to the scalar, and the roll is a deterministic `SplitMix64` over `(worldSeed, placementId, anchorKey)`.
- `NpcPlacementAuthoring.place` is the one writer behind `/zignpc place` and any consumer alias (owner-file write, owner-layer re-read, forced sweep); never add a second.
- After writing the owner switch file `mods/ziggfreedcommon/npc-placements.json`, call `NpcPlacementReconciler.forceSweep`, or the change waits for the next restart.
- `Props` (each `Item`, `Offset`, `Yaw`, `Scale`) decorate the placement's own spot: `Offset` is from the anchor plus the anchor's `Offset`, in world axes (the anchor's `Yaw` never turns it), and `Yaw` is degrees, turned into the engine's radians. With `Props`, `Identity.Role` is optional and a role-less placement stands no NPC; `Lifecycle`, `Interact` and `Identity.NpcId`/`Aliases` stay the NPC's (authored role-less, the `PROPS_ONLY_NPC_FIELDS` warning). `Item` is a plain string with `EditorSchema.assetRef`, so an unknown id skips that entry and is the `UNKNOWN_PROP_ITEM` warning, never a refused file.
- `Season` (zc-core `SeasonLeaf`) stands a placement only while that calendar event runs: `registry/PlacementGates` reads it third, after `Enabled` and the owner override and before `Requires`, denying with `placement.denied.season`, and it survives `Parent` whatever a child writes in `Requires`. The calendar's start and end sweeps move it with no `Requires` of its own.

## Audit

- `auditFileLocal` runs at every fold. The full cross-asset audit runs once, at the first `PlayerReadyEvent`, because asking earlier invents findings; a consumer that folds placements into its own report calls `claimLateAudit`. zc's boot audit takes that one run at `BootEvent` instead (`claimLateFindings`), and nothing where a consumer claimed it.
- The audit cannot check that a named role exists (roles are not an asset store); `NpcPlacementAuthoring.isSpawnable` is a courtesy check where someone types a role.
- `NpcPlacementConfig.rolesByPlacement()` feeds zc-encounter's `EncounterAudit` through the wiring root, so a placement role that is an encounter script id is reported under the encounter domain.
