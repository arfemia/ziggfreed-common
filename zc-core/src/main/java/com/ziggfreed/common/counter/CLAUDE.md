# CLAUDE.md - `counter/` (module `zc-core`)

Named long TALLIES per subject: how many times a thing has happened, and the best a subject ever
reached. Four small classes, no engine types anywhere, no domain vocabulary ever.

Package root `com.ziggfreed.common.counter`. Depends on `subject.Subject` and nothing else.

| Class | What it is |
|---|---|
| `CounterMap` | a bag of named tallies with no owner: `add`/`increment`/`set`/`highWater`/`mergeSums`/`mergeHighWater`/`all`/`copy`. Field-serializer friendly, so a file-backed table can hold one directly |
| `CounterStore` | THE persistence seam: `get`/`put`/`all`/`clear` per `Subject`, plus `markDirty`/`flush` for a store that batches |
| `InMemoryCounterStore` | the complete store that dies with the process (tests, session-scoped consumers) |
| `Counters` | the engine over a store: plain keys, CATEGORY keys, `totals`/`category`/`categories`/`snapshot`/`addAll` |

## Rules to keep

- **Two ways to move a tally, never interchangeable.** `add` accumulates ("how many times"),
  `highWater` raises a ceiling ("the best ever"). A run of 5 then a run of 4 must leave a best at 5,
  not 9. Which one a key uses is decided by whoever knows what the number MEANS, never by the call
  site that happens to have a value in hand.
- **A key at exactly zero is DROPPED, never stored as a zero.** `CounterMap` and `CounterStore`
  both follow it, so a reset leaves nothing behind and `isEmpty()` means what it says.
- **Grouping rides in the KEY.** `Counters.key(category, name)` joins with `/`, so one flat store
  serves a grand total plus any number of per-thing breakdowns, and a new category needs no schema
  change. `/` is therefore RESERVED in both halves - `Counters.isReservedName` is what a validator
  calls so a bad name is a load-time finding.
- **No domain vocabulary, no engine types.** This package counts; it has no idea what is being
  counted. No `Player`, no `Store`, no component, no consumer's key names.
- **`counter/` vs `stats/` never merge** (the rule is stated in full in
  [`stats/CLAUDE.md`](../stats/CLAUDE.md)): a "how many times has this subject done X" number is a
  tally and belongs here; a "what does this sword add to Attack Damage" number is an item-carried
  stat and belongs there. A consumer wanting both mirrors a tally onto a channel through
  `StatMirror` - it does not merge the packages.

## Consumers

- The instance leaderboard's lifetime view: `LeaderboardEntry` holds a `CounterMap` for its
  cumulative tallies (total points is the reserved key), so the record path and the cross-bucket
  aggregate share one summing authority.
- Any consumer keeping per-subject lifetime numbers builds a `Counters` over its own
  `CounterStore` (an ECS component, a database row) and keeps the key vocabulary on its own side.
- The MMO Skill Tree mod's `statistics.StatisticsComponent` is the production exemplar of exactly
  that: it implements `CounterStore` directly (a persisted per-player ECS component IS the store)
  and wires a fresh `Counters` engine over itself for every read/write, keeping every stat key name
  on the MMO's own side.
