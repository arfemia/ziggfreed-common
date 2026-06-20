# CLAUDE.md - worldmap/

Router for `com.ziggfreed.common.worldmap`: the ONE reusable seam for in-game world-map / compass markers, wrapping Hytale's native `WorldMapManager` so a consumer (a minigame, the MMO) never re-derives the `MapMarker` + `MapMarkerBuilder` + `MarkersCollector` plumbing.

- **[`WorldMapMarkers`](WorldMapMarkers.java)** - static utility:
  - `marker(id, icon, x, y, z, name)` - build a `MapMarker` (hides `MapMarkerBuilder` + `Transform`). `icon` = a client texture id (`"Portal.png"`, `"Home.png"`); `name` = an optional hover `Message` (a translation key resolves per-locale client-side).
  - **Global POI** (world-wide, all players; backed by the engine `POIMarkerProvider`, a `ConcurrentHashMap` so thread-safe): `place(world, id, x,y,z, icon, name)` / `place(world, marker)` / `remove(world, id)` / `clearAll(world)`.
  - **Per-player** (each player sees their own set, e.g. quest waypoints): `registerProvider(world, key, provider)` / `registerProvider(world, key, ignoreViewDistance, provider)` / `unregisterProvider(world, key)`, where `PlayerMarkerProvider.markersFor(world, player)` returns that player's markers.

- **[`MapDiscovery`](MapDiscovery.java)** + **[`DiscoveryMode`](DiscoveryMode.java)** - a "discoverable POIs" tracker COMPOSED over `WorldMapMarkers` (per-player provider): a set of points that stay HIDDEN until DISCOVERED, then surface as markers. Two orthogonal axes the consumer picks as policy:
  - **Trigger** = `DiscoveryMode { OFF, ON_INTERACT, PROXIMITY }`. `discover(poiId, uuid)` is the ON_INTERACT entry (returns `true` only on the FIRST discovery of a (POI, viewer) pair, so a one-time cue fires once); `revealWithin(Map<uuid,Vector3d> positions, radius)` is the PURE PROXIMITY entry the consumer calls from its OWN tick (no world read). `OFF` = the consumer never builds a tracker.
  - **Visibility** = `MapDiscovery.Visibility { PER_PLAYER, SHARED }`, per-POI: only the discoverer, or everyone once anyone finds it. One tracker hosts both.
  - Consumer holds one per context, `register`s POIs (lazily on interact, or all up front for proximity), `attach(world)` (enables the compass + registers ONE provider under a mod-prefixed key), `detach(world)` at end. `updateIcon(id, icon)` swaps an icon in place (e.g. an objective completing) keeping who discovered it. Generic: no consumer types, no baked ids/icons; display `Message`s come from the consumer.

## Gotchas

- **Rendering precondition:** markers only deliver while the world's compass or map is enabled (`World.isCompassUpdating() || isWorldMapEnabled()`). A bespoke instance world with both off shows nothing until `World.setCompassUpdating(true)`.
- **Reserved keys:** the engine pre-registers providers under `poi`, `spawn`, `respawn`, `death`, `personal`, `shared`, `playerIcons` - a consumer provider/marker id must avoid those (use a mod-prefixed key).
- Every method is try-guarded (no throw into the caller) and returns `boolean` success; a missing `WorldMapManager` is a no-op `false`.

First consumers: Kweebec Nightmare's exit marker at gate-open (global POI, gated by the `ExitMarker` preset knob, off for Hardcore), and `MapDiscovery` shrine markers on first-interact (per-difficulty `ShrineDiscovery`/`ShrineDiscoveryVisibility` knobs: Amateur SHARED, Nightmare PER_PLAYER, Hardcore OFF; lit shrines icon-swap).
