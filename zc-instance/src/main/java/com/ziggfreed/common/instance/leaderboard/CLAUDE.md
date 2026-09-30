# instance/leaderboard/

- A bucket key is the consumer's own string, except `*`, reserved for the synthesized All tab, and `total_points`, reserved for the total counter and never a consumer stat key.
- `Leaderboard.record` mutates in memory and schedules a debounced off-thread atomic flush; an unreadable file falls back to its `.bak`.
- `LeaderboardPage` keeps no state across events: every binding round-trips the full state, and every exit path sends a response.
- The stats row has four cells (`StatColumnDef.MAX_STAT_COLUMNS`); extra columns are dropped.
- `EncounterLeaderboardListener` only listens, and a binding row naming no `Bucket` writes nothing.
- A `LeaderboardLayoutAsset` difficulty tab's `Id` is the bucket prefix and must equal the preset id.
