# zc-instance

- Depends on `zc-core`, `zc-presentation`, `zc-loot` and `zc-encounter` (listen and read only: `EncounterLeaderboardListener` writes a defeat's rows, and `EncounterBoards` and `/zigleaderboard` read the binding rows that shape them). `zc-objectives` is its one library dependent.
- Never add an edge to `zc-objectives`, `zc-dialogue` or `zc-progression`. A finished round leaves as `InstanceRoundCompletedEvent`, fired through `InstanceRounds.fireCompleted` on the instance world thread; its `winners` list is empty on a loss or an abort.
- Every page takes an immutable `*PageDeps` plus a locale-free messages provider; the consumer supplies pre-built client-resolved `Message`s.
- A page names a player only through zc-presentation's `ui/name/PlayerDisplayNames.displayName`, on a Label's `.TextSpans` (a decorated name is a parameterized Message). `PlayerNamesGoThroughTheSeamTest` fails a page that reads `getUsername()` or writes a name on `.Text`; a bare username kept as data (a search haystack) carries `// NAME-DATA-OK: <reason>`. A notice about a player (the invite-sent toast) names them through `PlayerDisplayNames.plainName`, which carries no title.
- A consumer's own reward kind (such as `xp`) is a `RewardAuthoring` adapter on `RewardKinds.shared()`, never a table in this module.
