# worldmap/

- Markers render only while the world's compass or map is enabled; call `World.setCompassUpdating(true)` for a bespoke instance world.
- Never use a provider key the engine reserves (`poi`, `spawn`, `respawn`, `death`, `personal`, `shared`, `playerIcons`); use a mod-prefixed key.
- A per-player provider never derives identity from the `Player`: `registerProvider` resolves the viewer through `PlayerIdentityCache` and fails closed on a miss.
- `WaypointService.refresh` runs on the world thread; `markerSpecsFor` runs on the map tracker thread and reads only the snapshot and the resolver, never the entity store.
- Waypoint marker ids are `providerKey:targetId:anchorKey`, so two live copies of one place stay two markers.
