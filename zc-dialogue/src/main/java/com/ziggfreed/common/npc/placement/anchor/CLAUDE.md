# npc/placement/anchor/ - where an NPC may stand

Router for `com.ziggfreed.common.npc.placement.anchor`: positions and the indexes that discover
them. The engine's whole story is the parent [`../CLAUDE.md`](../CLAUDE.md); this is the map of
what lives here.

- **[`AnchorPosition`](AnchorPosition.java)** - `(kind, instanceId, x, y, z, yaw)`; `anchorKey()`
  is a PERSISTED format (a ledger key component), so changing it orphans every row.
- **[`StructureAnchorIndex`](StructureAnchorIndex.java)** +
  **[`PlacementMarkerSystem`](PlacementMarkerSystem.java)** +
  **[`StructureMarkerSightings`](StructureMarkerSightings.java)** - the structure driver: the
  system records marker sightings (keyed by FLOORED world position, queried on
  `SpawnMarkerEntity` ALONE) into the live index and the author-facing ring buffer, then kicks a
  sweep. The index is transient by design.
- **[`ZoneAnchorIndex`](ZoneAnchorIndex.java)** - `notifyZoneDiscovered(...)`: the engine owns the
  anchor, the consumer supplies the trigger.

Tests here: `StructureAnchorSightingTest`.
