# `world/placed/` - the placed-block ledger

Router for `com.ziggfreed.common.world.placed`. Two classes, one question: **did the player put that
block or item there themselves?** It is the reason place-then-break pays nothing.

## Why it is the LIBRARY's

Every reader has to get the SAME answer. This library's own break and pickup producers ask it, a
consumer's XP path asks it, a statistics counter asks it - and two ledgers would be two verdicts on
one break, so a server could hand out quest progress for a block it refused XP for. One authority
removes the disagreement structurally rather than by everybody remembering to agree.

## The pieces

| file | what it is |
|---|---|
| [`PlacedBlockLedger`](PlacedBlockLedger.java) | the ledger and the only reader-facing surface: `trackPlacement` / `consumePlacement` / `isPlaced` for positions, `trackPlacedItem` / `consumePlacedItem` for item ids, plus the policy, expiry sweep and persistence |
| [`PlacedBlockRecorder`](PlacedBlockRecorder.java) | the single ECS `PlaceBlockEvent` system that WRITES it, registered from the wiring root |

## Rules that bite

- **A consumer READS; it never records.** One recorder exists, in this library. A second one would
  raise the placed-item count once per installed mod, and a player who placed one block would then
  be refused their next several pickups of that item.
- **Positions are world-scoped; placed ITEMS are a COUNT.** An item lying on the ground has no
  coordinates to remember, so the item half remembers "this player put down N of these" and spends
  one per pickup. A caller with no world resolved must treat that as NOT placed - the same answer an
  unknown position gives - never pass a null world in.
- **ONE moment costs ONE row however many readers it has.** A single native break or pickup is read
  by several ECS systems in an order nobody specifies, and the first reader must neither take the
  answer away from the rest nor let the moment be charged twice. A BLOCK is keyed by position, so a
  consumed row keeps answering for `READ_GRACE_MS` and is dropped after. A placed ITEM has no
  position, so the reader NAMES the moment instead: `consumePlacedItem(uuid, itemId, momentKey)`,
  where the key is anything stable for that one event (the picked-up stack's own identity). Two
  readers with the same key inside the window are told the same answer for free.
- **`consumePlacement` is the break-time read and it SPENDS the row; `isPlaced` is the read for a
  caller that is only looking.** Asking the consuming one to observe the ledger changes what it is
  observing, which is why the non-consuming door exists at all.
- **`Policy` is four INDEPENDENT knobs read LIVE** (`enabled` / `strict` / `blockExpireMinutes` /
  `itemExpireMinutes`), so a consumer whose own owner config already carries them installs one
  policy at setup and a reload moves them with nothing re-pushed.
- **Fairness: the default is `strict`**, so nobody earns from a placement whoever breaks it. Setting
  `strict` false narrows the refusal to the placer alone, for a server that would rather a builder
  never poisoned the blocks their neighbours mine.
- **Three filters decide whether a placement counts, and they are ONE public predicate.**
  `PlacedBlockRecorder.placementCounts(cancelled, itemId, gameMode)` refuses a cancelled
  placement (nothing was put down), a null/blank/`Empty` item (nothing was in hand), and a
  CREATIVE-mode placement (an admin walling in an ore vein for survival players is the opposite
  of the exploit, and the block carries no signal at break time about who put it there). The
  library's own `PLACE_BLOCK` producer calls that same method rather than re-reading the event,
  so what is remembered as placed and what is produced as a moment can never disagree - a
  consumer that needs the same question answered calls the predicate, it never rewrites the
  three filters.

## Persistence

Blocks only, to `mods/ziggfreedcommon/placed-blocks.json`, loaded at setup. Two write paths share
one stamped, lock-serialized writer: a debounced traffic beat (riding `trackPlacement` AND
`consumePlacement`, at most one flush per `FLUSH_INTERVAL_MS`, CAS-claimed) snapshots the block
state on the world thread and hands it to a single-flight background writer (latest snapshot wins,
serialization + disk I/O off the world thread, fine-level logging), so a crash loses at most one
interval of placements; `save()` at shutdown stays synchronous and unconditional (a clean ledger
still writes, INFO-logged), awaits any in-flight background write first, and carries the newest
stamp so a background straggler can never roll the file back. A first consumption sets no dirty
flag: `consumedAt` is in-memory only and never serialized. Items are forgotten in minutes and a
restart takes longer than that, so saving them would only restore rows that were already stale.
The load and the recorder registration are guarded SEPARATELY at the wiring root: a failure in one
must not silently skip the other, because a ledger that loaded nothing answers "nobody placed
that" for every position saved by the last run.
