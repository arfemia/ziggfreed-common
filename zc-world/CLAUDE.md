# CLAUDE.md - zc-world

World targeting (the `Where` group, name-pattern matching, match ranking), atmosphere and
placement helpers, and the world-map POI/discovery layer. A world is named by what it is CALLED or
by the gameplay config it runs, and nothing in between - every world-targeting type in the library
(`Where`, dialogue's `World` condition, an NPC placement, a mob-scaling world file) speaks that one
vocabulary.

## Build

Part of the twelve-module `ziggfreed-common` build (`gradle/zc-module.gradle` convention, Java 25,
compiles as `:zc-world`). See the root [`CLAUDE.md`](../CLAUDE.md) for the aggregate build.

## Dependencies

- **Depends on**: `zc-core`, `zc-entity` (`SpawnPlacement`'s foliage-skip surface snap and the
  placement engine's identity resolution lean on entity-layer primitives).
- **Depended on by**: `zc-dialogue` (`DialogueCondition.World` resolves through the shared
  `WorldSelector` group; `WhereValidator` backs the placement engine's `Where` audit).
- **Reverse-edge trap**: none declared today. This module is targeting infrastructure, not domain
  content, so an edge upward to a domain module (dialogue, progression, instance) would mean world
  targeting had started depending on the very content it is supposed to gate.

## Packages

- [`world/`](src/main/java/com/ziggfreed/common/world/CLAUDE.md) - the embeddable `WorldSelector`
  group codec (`{Match, GameplayConfig, ExcludeMatch}`, authored under the key `Where`), the
  `MatchRank` specificity ladder (`GameplayConfig` exact > exact name > longest partial core >
  bare `*`), the `WhereValidator` audit, and `WorldIdentity`, the guarded read of which worlds the
  server currently has. **The pattern GRAMMAR and the ladder itself are zc-core's
  [`match/`](../zc-core/src/main/java/com/ziggfreed/common/match/CLAUDE.md)**: `WorldNameMatcher
  .Pattern` is a `NamePattern` and `MatchRank` is a `NameMatchRank` plus the one world-specific
  `GameplayConfig` rung, so a world-targeting field and any other name-matched field in the library
  parse and sort identically. Also `SurfaceProbe` (top-solid-Y column probe -> floor-snap) and
  `SpawnPlacement` (ring/near-player runtime spawn positions, foliage-skip surface snap).
- [`worldmap/`](src/main/java/com/ziggfreed/common/worldmap/CLAUDE.md) - `WorldMapMarkers`
  (global + per-player POI/compass markers over the native `WorldMapManager`), `MapDiscovery` (+
  `DiscoveryMode`: hidden-until-discovered POIs, trigger x visibility as two orthogonal knobs), and
  the waypoint mechanism (`WaypointService`/`WaypointSnapshots` over a `WaypointTargetSource` +
  `WaypointPositionResolver` pair).

## Shipped resources

None. This module is code only.

## Conventions

`{Match, GameplayConfig, ExcludeMatch}` under the key `Where` is the ONE spelling of "which
worlds?" everywhere in the library; a new world-targeting type embeds this group rather than
inventing its own axis. A bare word under `Match` is an EXACT world name, so a file targeting the
main world authors `["default"]` and reaches that world alone. The validator's
`MATCHES_NO_LOADED_WORLD` finding (run from a LATE audit, once the universe is up) makes a
misconfigured `Where` loud instead of silently matching nothing.

## Tests

7 files: `WorldSelectorMatchTest` (the two positive axes, the exclusion filter, the codec's
null-is-null contract, and the pin that a bare `default` is an exact name rather than a contains),
`WhereValidatorTest` (the shape findings plus the describes-a-real-world check and its "cannot
tell" contract), `MatchRankTest` (the world ladder: the shared bands plus the `GameplayConfig` rung
on top), `WorldNameMatcherTest` (the grammar as the world SELECTS through it - parses and scores,
never selects, per its own javadoc), `MapDiscoveryTest`, `WaypointSnapshotsTest`. The grammar's and
the ladder's own contracts are pinned once in zc-core (`NamePatternTest`, `NameMatchRankTest`);
these cover what the world adds.
