# npc/placement/

**Never place from absence alone.** A chunk unload removes an entity from the store, so a sweep cannot tell "never placed" from "placed, chunk asleep": placing needs a ledger miss AND a ticking anchor chunk section (on Update 7 a section sleeps whatever its column does). `Lifecycle.KeepAlive` is not a fix.

- The component id `ZiggfreedCommon:PlacedNpc` and `AnchorPosition.anchorKey()` are persisted formats: never rename or reshape them. A custom anchor resolver's `instanceId` must be stable across restarts, or each restart mints a duplicate.
- Query structure markers as `SpawnMarkerEntity` alone, keyed by floored world position (`anchor/PlacementMarkerSystem` says why).
