# npc/placement/runtime/ - the sweep and the two authorities

Router for `com.ziggfreed.common.npc.placement.runtime`: what actually stands NPCs up, keeps
exactly one of each, and takes them down. The engine's whole story (including the double-place
regression and the decision table) is the parent [`../CLAUDE.md`](../CLAUDE.md); this is the map
of what lives here.

- **[`NpcPlacementReconciler`](NpcPlacementReconciler.java)** - the correctness core: two pure
  decision cores (`decideResident`, `decidePlace`) plus the three-pass DESPAWN -> HEAL -> PLACE
  sweep, the in-flight claim set and the per-world debounce latch.
- **[`PlacedNpcComponent`](PlacedNpcComponent.java)** (`ZiggfreedCommon:PlacedNpc`, a PERSISTED
  registry id) - the despawn/orphan authority; its pure snapshot is
  [`PlacedNpcIdentity`](PlacedNpcIdentity.java).
- **[`NpcPlacementLedger`](NpcPlacementLedger.java)** - the place authority: persisted
  `(world | placementId | anchorKey) -> uuid` rows at
  `mods/ziggfreedcommon/npc-placement-ledger.json`.
- **[`NpcPlacementService`](NpcPlacementService.java)** - thin policy over `../NpcSpawnService`:
  `place` / `despawn` / `releaseInstance` / `fortify` / `pinChunk` / `roleFor`.
- **[`PlacementAnchors`](PlacementAnchors.java)** - the union/limits engine: anchor-group union,
  `MaxPerWorld` across it, `OncePerWorld` collapse in declaration order, the deterministic
  `SplitMix64` chance roll, and `resolveChance` (formula over scalar).
- **[`PlacementKeepAlivePins`](PlacementKeepAlivePins.java)** - refcounted chunk pins
  (pin on first insert, unpin on last removal, world dropped by evictor).
- **[`NpcPlacementPositionCache`](NpcPlacementPositionCache.java)** - `(worldName, placementId,
  anchorKey)`-keyed convenience for waypoints; never an authority.
- **[`PlacementDiag`](PlacementDiag.java)** - package-private once-per-key diagnostics for the
  reconciler and anchors; deliberately in this package so `once()` needs no wider visibility.

Tests here: `NpcPlacementReconcilerTest`, `PlacementAnchorsTest`, `PlacementKeepAlivePinsTest`.
