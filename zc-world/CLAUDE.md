# CLAUDE.md - zc-world

World identity (selectors, name index, match ranking), atmosphere and placement helpers, and the
world-map POI/discovery layer. A world selector is a NAMED, REUSABLE MATCHER, not an opaque tag -
every selector-aware type in the library (`Where`, dialogue's `World` condition, an NPC placement,
a mob-scaling world file) speaks this one vocabulary.

## Build

Part of the twelve-module `ziggfreed-common` build (`gradle/zc-module.gradle` convention, Java 25,
compiles as `:zc-world`). See the root [`CLAUDE.md`](../CLAUDE.md) for the aggregate build.

## Dependencies

- **Depends on**: `zc-core`, `zc-entity` (`SpawnPlacement`'s foliage-skip surface snap and the
  placement engine's identity resolution lean on entity-layer primitives).
- **Depended on by**: `zc-dialogue` (`DialogueCondition.World` resolves through the shared
  `WorldSelector` group; `WorldIdentity` backs the placement engine's `Where` gate).
- **Reverse-edge trap**: none declared today. This module is identity infrastructure, not domain
  content, so an edge upward to a domain module (dialogue, progression, instance) would mean world
  identity had started depending on the very content it is supposed to gate.

## Packages

- [`world/`](src/main/java/com/ziggfreed/common/world/CLAUDE.md) - `WorldSelectorAsset` +
  `WorldSelectorConfig` (`Server/ZiggfreedCommon/WorldSelectors/`), the embeddable `WorldSelector`
  group codec (`{Names, Match, GameplayConfig, ExcludeNames}`), the `MatchRank` specificity ladder
  (`GameplayConfig` exact > exact name > longest partial core > bare `*`), and the cached
  `WorldIdentity` resolver. `ExcludeNames` resolves in two passes (positive names first, exclusions
  applied against that fixed set) so a world's names never depend on fold order. `WorldSelectorOverrides`
  reads the owner layer `mods/ziggfreedcommon/world-selectors.json` (id -> whole selector body,
  entry replaces by id). Also `SurfaceProbe` (top-solid-Y column probe -> floor-snap) and
  `SpawnPlacement` (ring/near-player runtime spawn positions, foliage-skip surface snap).
- [`worldmap/`](src/main/java/com/ziggfreed/common/worldmap/CLAUDE.md) - `WorldMapMarkers`
  (global + per-player POI/compass markers over the native `WorldMapManager`), `MapDiscovery` (+
  `DiscoveryMode`: hidden-until-discovered POIs, trigger x visibility as two orthogonal knobs), and
  the waypoint mechanism (`WaypointService`/`WaypointSnapshots` over a `WaypointTargetSource` +
  `WaypointPositionResolver` pair).

## Shipped resources

`Server/ZiggfreedCommon/WorldSelectors/{Zc_Any.json, Zc_Default.json}` - the structural `any` (`*`)
and `default` (the stock main world) selectors every other selector-aware file is written against.
These are the one deliberate exception to "common ships zero content": they are STRUCTURAL
vocabulary, not gameplay content. A server whose main world is named something other than `default`
re-points `Zc_Default` through the owner layer rather than editing this file.

## Conventions

`WorldIdentity.invalidateAll()` on every asset merge is mandatory - the main world is added BEFORE
assets fold, so skipping the invalidation caches an empty name set for the life of the process.
`{Names, Match, GameplayConfig, ExcludeNames}` is the ONE spelling of "which worlds?" everywhere in
the library; a new selector-aware type embeds this group under a `Where` key rather than inventing
its own axis. The validator's `MATCHES_NO_LOADED_WORLD` finding (both def-level and inline-selector
forms) makes a misconfigured selector loud instead of silently matching nothing.

## Tests

9 files: `WorldSelectorAssetTest`, `WorldSelectorMatchTest`, `WorldSelectorValidatorTest`,
`WorldSelectorOverridesTest` (the owner-layer re-point), `WorldIdentityTest`, `MatchRankTest`,
`WorldNameMatcherTest` (the pure pattern grammar - parses and scores, never selects, per its own
javadoc), `MapDiscoveryTest`, `WaypointSnapshotsTest`.
