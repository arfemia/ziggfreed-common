# npc/placement/registry/ - the open registries and the gate chain

Router for `com.ziggfreed.common.npc.placement.registry`: the third/fourth-party extension points
and the veto chain. The engine's whole story is the parent [`../CLAUDE.md`](../CLAUDE.md); this is
the map of what lives here. Each registry is JVM-global, case-insensitive, last-write-wins, and
warns ONCE per unknown id.

- **[`PlacementRegistryLedger`](PlacementRegistryLedger.java)** - this engine's one-line naming of
  zc-core's `registry/RegistryLedger` (fixes the `[placement]` log label; every semantic is
  inherited).
- **[`PlacementFactorRegistry`](PlacementFactorRegistry.java)** - the static facade over ONE
  shared `factor/FactorRegistry` instance; `firstFailure(requires, ...)` evaluates a whole
  `Requires` with the placement id as payload and NO subject. Fails closed.
- **[`AnchorResolverRegistry`](AnchorResolverRegistry.java)** - custom anchors behind
  `Anchor.Custom{Provider,Params}`; a resolver's `instanceId` must be stable across restarts.
- **[`PlacementGate`](PlacementGate.java)** + **[`PlacementGates`](PlacementGates.java)** - the
  ordered veto chain (any deny wins, first deny reported, a throwing gate is skipped); a deny
  despawns the standing NPC on the next sweep.

Tests here: `PlacementRegistryLedgerTest`, `PlacementRegistryTest`, `PlacementGateChainTest`,
`PlacementChanceFormulaTest`, and `NpcPlacementAuditScopeTest` (which findings the fold-time
audit may answer, needing these registries cleared).
