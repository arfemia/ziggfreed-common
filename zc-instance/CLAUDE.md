# CLAUDE.md - zc-instance

The instance-experience layer: arena, encounter director, match rules, play/queue and results
screens, presets, leaderboards, plus the lobby and party systems that feed them. This is the
"a consumer minigame gets a full end-game + party/queue framework for free" module.

## Build

Part of the twelve-module `ziggfreed-common` build (`gradle/zc-module.gradle` convention, Java 25,
compiles as `:zc-instance`). See the root [`CLAUDE.md`](../CLAUDE.md) for the aggregate build.

## Dependencies

- **Depends on**: `zc-core`, `zc-presentation` (every screen here is a page), `zc-loot` (rewards
  and the reward model the results screen renders).
- **Depended on by**: `zc-objectives`, for the round-completion seam below and nothing else (its
  `ZigInstanceRoundProducer` listens for `InstanceRoundCompletedEvent`). A consumer minigame
  (Kweebec is the exemplar) depends on this module directly for its end-game layer.
- **Reverse-edge trap**: `zc-objectives` now sits ABOVE this module, so an import of anything under
  `objectives` from here would close a cycle - a round moment travels OUT as a native event and
  never as a call into the progression runtime. The same holds for `zc-dialogue` and
  `zc-progression`: this module is domain content (matches, arenas, parties), so an edge upward to
  any of the three would be a domain module reaching into a peer domain rather than through the
  wiring root.

## Packages

- `instance/arena/`, `instance/encounter/`, `instance/leaderboard/`, `instance/match/`,
  `instance/metadata/`, `instance/play/`, `instance/zone/` each have their own router (see the
  links below); `instance/preset/` and `instance/result/` do not (small, self-contained).
  - [`instance/arena/`](src/main/java/com/ziggfreed/common/instance/arena/CLAUDE.md) -
    `ArenaDefinitionAsset`, the instance arena content type.
  - [`instance/encounter/`](src/main/java/com/ziggfreed/common/instance/encounter/CLAUDE.md) - the
    reusable ENCOUNTER framework's spawn half: `SpawnRoster<T>`/`SpawnUnit<T>` (weighted,
    tier-gated picks) + `EncounterDirector` (cooldown/cap/per-round-max gating), pure logic, no
    engine deps. `MultiPhaseBossAsset` + `EncounterRuleAsset` are the authoring layer.
  - [`instance/leaderboard/`](src/main/java/com/ziggfreed/common/instance/leaderboard/CLAUDE.md) -
    `Leaderboard` + `LeaderboardPage`, generalized bucketed scoring, `LeaderboardLayoutAsset`.
  - [`instance/match/`](src/main/java/com/ziggfreed/common/instance/match/CLAUDE.md) - match rules
    + verdict.
  - [`instance/metadata/`](src/main/java/com/ziggfreed/common/instance/metadata/CLAUDE.md) -
    `RoundMetadata`, the outbound integration envelope, plus the round-completion seam below.
  - [`instance/play/`](src/main/java/com/ziggfreed/common/instance/play/CLAUDE.md) - `PlayModePage`,
    the asset-driven Public/Party/Solo chooser that morphs into a live launch-timer roster.
  - `instance/preset/` - `InstancePresetAsset`, the codec-asset-driven instance preset (names a
    `Lootable` by `RewardTableId`). No router of its own.
  - `instance/result/` - `MatchResult` + `ResultsPage`, the end-game team breakdown + reward chips
    + leaderboard CTA. No router of its own.
  - [`instance/zone/`](src/main/java/com/ziggfreed/common/instance/zone/CLAUDE.md) -
    `ZoneHoldTimer`, the co-op "hold this zone TOGETHER for X seconds, reset on break" objective
    timer. Pure logic.
- [`lobby/`](src/main/java/com/ziggfreed/common/lobby/CLAUDE.md) - `MatchmakingQueue`/
  `LobbyService`/`RoundLauncher`, the generic fill-window + countdown matchmaking queue.
- [`party/`](src/main/java/com/ziggfreed/common/party/CLAUDE.md) - `Party`/`PartyService` +
  `PartyInvitePage`, `PartySettingsAsset`. `party/page/` (the invite-page rendering split) has no
  router of its own; the parent `party/` router covers it.

## The round completion seam

`InstanceRoundCompletedEvent` + `InstanceRounds.fireCompleted` (both in
[`instance/metadata/`](src/main/java/com/ziggfreed/common/instance/metadata/CLAUDE.md)) are the ONE
generic "a round is over" moment any minigame built on this module fires. It carries the flat
`RoundMetadata` envelope plus `participants` and `winners` (immutable copies; `winners` equals
`participants` on a co-op win, the winning team on PvP, EMPTY on a loss or an abort, which is what
makes `isWin()` answerable with no outcome enum to interpret).

**Fire it on the instance world thread.** Dispatch is synchronous on the calling thread and guarded
on `hasListener()`, so a server with nobody listening pays nothing; a listener that needs a `Store`
hops with `world.execute` itself rather than assuming which world it woke up on. A consumer keeps
firing its own richer event beside this one - the two are not alternatives.

The first listener is zc-objectives' `ZigInstanceRoundProducer`, which turns a completion into
`INSTANCE_ROUND_ENDED` per participant and `INSTANCE_ROUND_WON` per winner. That direction is the
only one allowed: this module announces, and never asks anything about progression.

## Shipped resources

`Common/UI/Custom/Pages/{ZigLeaderboardPage.ui, ZigLeaderboardRow.ui, ZigLeaderboardStatsRow.ui,
ZigLeaderboardTab.ui, ZigPartyPage.ui, ZigPartyRow.ui, ZigPlayModeCard.ui, ZigPlayPage.ui,
ZigQueueRosterRow.ui, ZigResultChip.ui, ZigResultRow.ui, ZigResultsPage.ui}` - every screen in the
instance-experience layer. No `Server/` content beyond the registered asset TYPES (arenas, bosses,
encounter rules, leaderboard layouts, party settings, presets), all defaults-optional.

## Conventions

Each page takes an immutable `*PageDeps` + a locale-free `*Messages`/`*ScreenMessages` provider
(the consumer supplies pre-built client-resolved `Message`s); pages reuse the shared
`ZigFrames.ui`/`ZigButtons.ui` styles and the `zc-presentation` toast engine for in-page reward/
feedback toasts. Reward chips render an item icon by id + an auto-generated "x{n} {ItemName}" label
from the item's own engine display name - no per-reward `displayKey` needed. A consumer contributes
its own reward-spec kind (e.g. `xp`) by registering a `RewardAuthoring` adapter on
`RewardKinds.shared()` rather than this module holding a table of its own.

## Tests

6 files, thin relative to the package count: `LeaderboardTest`, `MatchmakingQueueTest`,
`PartyServiceTest`, `QueueModeSetTest`, `WinConditionResolverTest`, `ZoneHoldTimerTest`. The page
rendering itself (every `.ui` file listed above) has no unit coverage; `.ui` files are not compiled
and validate in-game per the library-wide rule.
