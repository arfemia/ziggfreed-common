# worldmap/

- Markers render only while the world's compass or map is enabled; call `World.setCompassUpdating(true)` for a bespoke instance world.
- Never use a provider key the engine reserves (`poi`, `spawn`, `respawn`, `death`, `personal`, `shared`, `playerIcons`); use a mod-prefixed key.
- A per-player provider never derives identity from the `Player`: `registerProvider` resolves the viewer through `PlayerIdentityCache` and fails closed on a miss.
- `WaypointService.refresh` runs on the world thread; `markerSpecsFor` runs on the map tracker thread and reads only the snapshot and the resolver, never the entity store.
- Waypoint marker ids are `providerKey:targetId:anchorKey`, so two live copies of one place stay two markers.
- A resolver learns where the viewer stands only from the `WaypointViewer` the service hands it, which `WorldMapMarkers.viewerOf` reads off the transform the engine's map tracker already holds on that thread; never look a viewer up in a store from a provider.
- `WaypointService` registers once per live world by `worldKey` (the world's uuid): `World` equality is by name, and a pinned instance comes back under its name as a new world.
- `Gateways` (`Server/ZiggfreedCommon/Gateways/`) says where the way into another world stands and never opens or gates it. `PortalGateways` reads the base game's portals into the defaults layer (a block drawing a map marker whose interaction teleports into an instance; a file of that block's id replaces it whole, never extends it as a `Parent`), `Blocks` finds each copy the engine recorded in `BlockMapMarkersResource`, `Positions` adds fixed spots, and a target is reached through a gateway when its own `Where` matches `Into`. `index(World)` snapshots on the world thread and a removed world's entry goes through `WorldEvictors`; the map thread reads `positions` and `nearest`.
