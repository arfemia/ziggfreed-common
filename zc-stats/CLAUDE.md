# zc-stats

The player stat store: one saved record of every player's tallies, fed by the library's own producers on any server.

- No library module may depend on this one; a consumer counts and reads through `playerstats/PlayerStats` only, never a record of its own. The package is `playerstats`, never `stats` (already split between zc-core and zc-entity).
- `PlayerStatsComponent` (save key `ZiggfreedCommon:Stats`) holds three strings that only grow: `Tallies` (lifetime, `key:value|...` through zc-core's `counter/PackedTallies`), `Seasons` (`<event>@<year>/<stat key>`) and `Imports` (claim ids). Append a field, never rename one; a damaged pair costs only itself and a key no stat registers rides along.
- Keys come only from `StatKeys`: `<Stat>` is the total, `<Stat>/<thing>` one breakdown line, matched without regard to case. A stat id never carries `/ @ | :`; a thing never carries `|`.
- It holds counted stats only (Sum, Max, Min). A Gauge and a native reading are ranked on the board, never stored here.
- A stat is a file, `Server/ZiggfreedCommon/Stats/<Id>.json`, shipped by whoever feeds it, so a zc-only server shows no stat nobody counts. Its `From` entries are the shared objective leaf plus `Count` (`Moment` or `One`); the leaf's own `Amount` means nothing here.
- Count off the shared moment stream (`StatsMomentListener`); an ECS system only for what no producer fires (a player's death, playtime). A stat counts a moment once, at its first matching `From` entry.
- `importOnce` builds the merge aside and swaps and claims in one synchronized step; it runs with the store switched off, since it moves data rather than counting.
- Off means absent: `$Enabled` in `mods/ziggfreedcommon/stats.json` (`StatsSwitch`) stops all counting and ranking, and the `ziggfreedcommon:counter` source answers null. The file's other entries belong to the board.
- The store writes zc-instance's stat board only through `StatRankFeed` (`board/StatBoardRankFeed`, installed at setup): a feed that throws costs its ranking, never the count. It answers the board's `StatBoardSource` from the stat files (`board/StatsBoardSource`); the board owns sections, periods and windows.
