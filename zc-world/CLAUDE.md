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
  `WorldSelector` group; `WhereValidator` backs the placement engine's `Where` audit),
  `zc-objectives` (`world/placed/PlacedBlockLedger` only - its block-break and pickup producers
  refuse to credit a block the breaker put down themselves).
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
- [`world/placed/`](src/main/java/com/ziggfreed/common/world/placed/CLAUDE.md) - the placed-block
  ledger; has its own router, read it before touching either class. `PlacedBlockLedger` remembers which positions and
  which item ids a player put down (world-scoped, expiring, gson-persisted to
  `mods/ziggfreedcommon/placed-blocks.json`) and answers the one question "did the breaker place
  this?"; `PlacedBlockRecorder` is the single ECS `PlaceBlockEvent` system that writes it, wired
  from the wiring root. **The ledger is the LIBRARY's, not a consumer's**: any mod counting block
  breaks or pickups wants the same refusal, and one authority is what keeps XP, statistics, quests
  and achievements from disagreeing about a single break. Its `Policy` is four independent knobs
  (`enabled` / `strict` / `blockExpireMinutes` / `itemExpireMinutes`) read LIVE, so a consumer whose
  own config already carries them installs a policy once and a reload moves them with no re-push. Several
  systems read ONE native event in an order nobody specifies, so a consumed BLOCK row keeps
  answering for `READ_GRACE_MS` (a position is the moment) and a placed-ITEM read names its moment
  (`consumePlacedItem(uuid, itemId, momentKey)`) so one pickup spends exactly one remembered copy
  no matter how many mods read it. **Fairness**: the default is `strict`, so nobody earns from a
  placement at all; setting `strict` false narrows the refusal to the placer alone, for a server
  that would rather a builder never poisoned their neighbours' blocks.
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

8 files: `PlacedBlockLedgerTest` (the ledger's whole contract: the strict and fairness readings,
the several-readers grace window, aging out driven from a hand-written file, and the persistence
round-trip that saves placements and deliberately drops placed items), `WorldSelectorMatchTest` (the two positive axes, the exclusion filter, the codec's
null-is-null contract, and the pin that a bare `default` is an exact name rather than a contains),
`WhereValidatorTest` (the shape findings plus the describes-a-real-world check and its "cannot
tell" contract), `MatchRankTest` (the world ladder: the shared bands plus the `GameplayConfig` rung
on top), `WorldNameMatcherTest` (the grammar as the world SELECTS through it - parses and scores,
never selects, per its own javadoc), `MapDiscoveryTest`, `WaypointSnapshotsTest`. The grammar's and
the ladder's own contracts are pinned once in zc-core (`NamePatternTest`, `NameMatchRankTest`);
these cover what the world adds.
