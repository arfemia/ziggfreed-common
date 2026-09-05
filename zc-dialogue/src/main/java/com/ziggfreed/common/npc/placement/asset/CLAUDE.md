# npc/placement/asset/ - the placement asset and its fold

Router for `com.ziggfreed.common.npc.placement.asset`: what a placement FILE says and how the
layers fold. The engine's whole story (authoring rules, the two authorities, the sweep) is the
parent [`../CLAUDE.md`](../CLAUDE.md); this is the map of what lives here.

- **[`NpcPlacementAsset`](NpcPlacementAsset.java)** - the Pattern A codec, every leaf
  `appendInherited` for native `Parent` reuse. Groups: `Identity` / `Where` / `Anchor` /
  `Requires` / `Limits` / `Lifecycle` / `Interact`.
- **[`NpcPlacementConfig`](NpcPlacementConfig.java)** - the `defaults < pack < owner` fold
  singleton; every merge clears the sweep debounce + position cache and logs the FILE-LOCAL
  findings; `runLateAudit()` runs the full audit once per boot and stands down when a consumer
  `claimLateAudit`s it; `rolesByPlacement()` is a cross-module read - a fresh `placementId -> [role]`
  map the wiring root hands to zc-encounter's `EncounterAudit.addRoleNameSource`, so a placement
  naming a role that resolves to a loaded encounter script is reported as
  `ENCOUNTER_SCRIPT_ID_IS_ROLE_ID` under the `encounter` domain, not this one.
- **[`NpcPlacementOverrides`](NpcPlacementOverrides.java)** - the owner switch at
  `mods/ziggfreedcommon/npc-placements.json` (exact > longest `*`-prefix > bare `*`).
- **[`NpcPlacementAuthoring`](NpcPlacementAuthoring.java)** - writing a placement from OUTSIDE a
  pack: `place(...)` is the one implementation behind `/zignpc place` and any consumer alias (it
  writes an ordinary `Identity` / `Where` / `Anchor.Coords` / `Interact` entry into the owner file,
  re-reads the owner layer, then force-sweeps), refusing an id that already exists and reporting
  `PLACED` / `ID_TAKEN` / `ROLE_NOT_SPAWNABLE` / `WRITE_FAILED` for the caller to word. Plus the
  courtesy role checks (`isSpawnable` / `spawnableRoles`) the command and admin page consult where
  somebody TYPES a role id; permissive when there is no registry to ask.
- **[`NpcPlacementValidator`](NpcPlacementValidator.java)** - the two audit entry points:
  `auditFileLocal` (shape / spelling / self-contradiction, safe at any boot stage) and `audit`
  (plus the cross-asset half, trustworthy only once everything is up).

Tests here: `NpcPlacementAssetCodecTest`, `NpcPlacementOverridesFileTest`,
`NpcPlacementValidatorTest`, `NpcPlacementConfigAuditTest` (the config's fold-vs-late moments).
