# CLAUDE.md - worldmap/

Router for `com.ziggfreed.common.worldmap`: the ONE reusable seam for in-game world-map / compass markers, wrapping Hytale's native `WorldMapManager` so a consumer (a minigame, the MMO) never re-derives the `MapMarker` + `MapMarkerBuilder` + `MarkersCollector` plumbing.

- **[`WorldMapMarkers`](WorldMapMarkers.java)** - static utility:
  - `marker(id, icon, x, y, z, name)` - build a `MapMarker` (hides `MapMarkerBuilder` + `Transform`). `icon` = a client texture id (`"Portal.png"`, `"Home.png"`); `name` = an optional hover `Message` (a translation key resolves per-locale client-side).
  - **Global POI** (world-wide, all players; backed by the engine `POIMarkerProvider`, a `ConcurrentHashMap` so thread-safe): `place(world, id, x,y,z, icon, name)` / `place(world, marker)` / `remove(world, id)` / `clearAll(world)`.
  - **Per-player** (each player sees their own set, e.g. quest waypoints): `registerProvider(world, key, provider)` / `registerProvider(world, key, ignoreViewDistance, provider)` / `unregisterProvider(world, key)`, where `PlayerMarkerProvider.markersFor(world, player, viewerId)` returns that player's markers.
  - **`viewerId` is resolved HERE, once, and nowhere else.** The engine hands the callback a bare `Player` on the world-map tracker thread, which has no supported identity accessor at all (a `store.getComponent` is illegal off the world thread), so `registerProvider` looks the uuid up in [`entity/PlayerIdentityCache`](../../../../../../../../zc-entity/src/main/java/com/ziggfreed/common/entity/PlayerIdentityCache.java) and passes it down. A cache MISS fails CLOSED: no markers that update, one warn per registration. A provider therefore never derives an identity from the `Player` itself.

- **[`MapDiscovery`](MapDiscovery.java)** + **[`DiscoveryMode`](DiscoveryMode.java)** - a "discoverable POIs" tracker COMPOSED over `WorldMapMarkers` (per-player provider): a set of points that stay HIDDEN until DISCOVERED, then surface as markers. Two orthogonal axes the consumer picks as policy:
  - **Trigger** = `DiscoveryMode { OFF, ON_INTERACT, PROXIMITY }`. `discover(poiId, uuid)` is the ON_INTERACT entry (returns `true` only on the FIRST discovery of a (POI, viewer) pair, so a one-time cue fires once); `revealWithin(Map<uuid,Vector3d> positions, radius)` is the PURE PROXIMITY entry the consumer calls from its OWN tick (no world read). `OFF` = the consumer never builds a tracker.
  - **Visibility** = `MapDiscovery.Visibility { PER_PLAYER, SHARED }`, per-POI: only the discoverer, or everyone once anyone finds it. One tracker hosts both.
  - Consumer holds one per context, `register`s POIs (lazily on interact, or all up front for proximity), `attach(world)` (enables the compass + registers ONE provider under a mod-prefixed key), `detach(world)` at end. `updateIcon(id, icon)` swaps an icon in place (e.g. an objective completing) keeping who discovered it. Generic: no consumer types, no baked ids/icons; display `Message`s come from the consumer.

- **[`WaypointService`](WaypointService.java)** + **[`WaypointSnapshots`](WaypointSnapshots.java)** - the "show me where to go next" MECHANISM, COMPOSED over `WorldMapMarkers` (per-player provider). A consumer supplies only the two things that are its own business and gets everything between:
  - **WHY** somebody is pointed somewhere = [`WaypointTargetSource`](WaypointTargetSource.java) (`targetsFor(viewerId)` -> [`WaypointTarget`](WaypointTarget.java)s). Sources are ADDITIVE and independent: registering a second reason is one `addSource` call and nothing here changes. Registration order is the precedence for a repeated target id.
  - **WHERE** a named place actually is = [`WaypointPositionResolver`](WaypointPositionResolver.java) (`resolve(worldName, positionKey)` -> [`WaypointPosition`](WaypointPosition.java)s). A key that resolves nowhere in this world draws nothing here, which is how a viewer is never pointed at another world's copy.
  - `builder(providerKey)` knobs, all independent: `defaultIcon`, `positionResolver`, `ignoreViewDistance` (default true - a waypoint is usually off-screen), `forceCompass` (default true), `warn`. `registerForWorld(world)` is idempotent per world; `refresh(viewerId)` / `set(viewerId, targets)` / `clear(viewerId)` drive the snapshot.
  - **The thread split IS the design.** `refresh` runs on the consumer's own (world) thread; `markerSpecsFor` runs on the map tracker and only reads the concurrent snapshot plus the resolver, never the entity store. `WaypointSnapshots` is that whole rule with no world in it, so it is unit-testable and a consumer wanting the rule without the binding can hold one directly.
  - Marker ids are `providerKey:targetId:anchorKey`, so two live copies of one place stay two markers.

## Gotchas

- **Rendering precondition:** markers only deliver while the world's compass or map is enabled (`World.isCompassUpdating() || isWorldMapEnabled()`). A bespoke instance world with both off shows nothing until `World.setCompassUpdating(true)`.
- **Reserved keys:** the engine pre-registers providers under `poi`, `spawn`, `respawn`, `death`, `personal`, `shared`, `playerIcons` - a consumer provider/marker id must avoid those (use a mod-prefixed key).
- Every method is try-guarded (no throw into the caller) and returns `boolean` success; a missing `WorldMapManager` is a no-op `false`.

First consumers: Kweebec Nightmare's exit marker at gate-open (global POI, gated by the `ExitMarker` preset knob, off for Hardcore), and `MapDiscovery` shrine markers on first-interact (per-difficulty `ShrineDiscovery`/`ShrineDiscoveryVisibility` knobs: Amateur SHARED, Nightmare PER_PLAYER, Hardcore OFF; lit shrines icon-swap).
