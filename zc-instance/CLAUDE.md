# zc-instance

The instance-experience layer a minigame builds on: arenas, match rules, presets, the play, results, leaderboard and party screens, the lobby and the party system.

- Depends on `zc-core`, `zc-presentation`, `zc-loot` and `zc-encounter` (listen-only, for `EncounterLeaderboardListener`). `zc-objectives` is its one library dependent.
- Never add an edge to `zc-objectives`, `zc-dialogue` or `zc-progression`. A finished round leaves as `InstanceRoundCompletedEvent`, fired through `InstanceRounds.fireCompleted` on the instance world thread; its `winners` list is empty on a loss or an abort.
- Every page takes an immutable `*PageDeps` plus a locale-free messages provider; the consumer supplies pre-built client-resolved `Message`s.
- A consumer's own reward kind (such as `xp`) is a `RewardAuthoring` adapter on `RewardKinds.shared()`, never a table in this module.
