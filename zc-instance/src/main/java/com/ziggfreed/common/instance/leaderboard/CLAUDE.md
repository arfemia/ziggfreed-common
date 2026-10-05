# instance/leaderboard/

- A bucket key is the consumer's own string, except `*`, reserved for the synthesized All tab, and `total_points`, reserved for the total counter and never a consumer stat key.
- `Leaderboard.record` mutates in memory and schedules a debounced off-thread atomic flush; an unreadable file falls back to its `.bak`.
- `LeaderboardPage` keeps no state across events: every binding round-trips the full state, and every exit path sends a response.
- The stats row has four cells (`StatColumnDef.MAX_STAT_COLUMNS`); extra columns are dropped.
- `EncounterLeaderboardListener` only listens, and a binding row naming no `Bucket` writes nothing.
- A `LeaderboardLayoutAsset` difficulty tab's `Id` is the bucket prefix and must equal the preset id on a round's board; a boss fight's layout is named after its bucket, and its tab ids are the difficulty labels and party sizes its rows were recorded under.
- With two axes a page names a selection's bucket through its deps (`LeaderboardPageDeps.bucketKey`; `UNDERSCORE` unless the board composes its own), and a page never hard-wires a separator.
- `EncounterBoards` reads one boss fight's rows back: the binding row's split picks the axes (difficulty over party size), the board's keys the tabs, a layout named after the bucket the labels and Stats columns. Every tab key, and a two-axis pair through the deps' composer, is built by the listener's own `bucketFor`, so the page reads exactly what the listener writes.
- The page paints its lines on `.Text` sinks (a `TextButton`, a header Label), which resolve a translation but never substitute a parameter: a `LeaderboardScreenMessages` line other than `yourRank` is a bare translation, and an unnamed tab shows its recorded value as raw text. The module's own words live in `ziggfreedcommon.leaderboard.lang`.
- The footer (`yourRank`) is the page's one parameterized line and is painted on `#YourRank.TextSpans`. `/zigleaderboard encounter [--encounter=<script id>]` (`command/`, adventurer group, registered by `InstanceBootstrap.installEncounterLeaderboard`) opens a fight's records for any player, the only fight on record when none is named.
