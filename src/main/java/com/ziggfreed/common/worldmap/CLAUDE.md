# CLAUDE.md - worldmap/

Router for `com.ziggfreed.common.worldmap`: the ONE reusable seam for in-game world-map / compass markers, wrapping Hytale's native `WorldMapManager` so a consumer (a minigame, the MMO) never re-derives the `MapMarker` + `MapMarkerBuilder` + `MarkersCollector` plumbing.

- **[`WorldMapMarkers`](WorldMapMarkers.java)** - static utility:
  - `marker(id, icon, x, y, z, name)` - build a `MapMarker` (hides `MapMarkerBuilder` + `Transform`). `icon` = a client texture id (`"Portal.png"`, `"Home.png"`); `name` = an optional hover `Message` (a translation key resolves per-locale client-side).
  - **Global POI** (world-wide, all players; backed by the engine `POIMarkerProvider`, a `ConcurrentHashMap` so thread-safe): `place(world, id, x,y,z, icon, name)` / `place(world, marker)` / `remove(world, id)` / `clearAll(world)`.
  - **Per-player** (each player sees their own set, e.g. quest waypoints): `registerProvider(world, key, provider)` / `registerProvider(world, key, ignoreViewDistance, provider)` / `unregisterProvider(world, key)`, where `PlayerMarkerProvider.markersFor(world, player)` returns that player's markers.

## Gotchas

- **Rendering precondition:** markers only deliver while the world's compass or map is enabled (`World.isCompassUpdating() || isWorldMapEnabled()`). A bespoke instance world with both off shows nothing until `World.setCompassUpdating(true)`.
- **Reserved keys:** the engine pre-registers providers under `poi`, `spawn`, `respawn`, `death`, `personal`, `shared`, `playerIcons` - a consumer provider/marker id must avoid those (use a mod-prefixed key).
- Every method is try-guarded (no throw into the caller) and returns `boolean` success; a missing `WorldMapManager` is a no-op `false`.

First consumer: Kweebec Nightmare's exit marker at gate-open (global POI, gated by the `ExitMarker` preset knob, off for Hardcore).
