# `world/placed/` - the placed-block ledger

Router for `com.ziggfreed.common.world.placed`. Four classes, one question: **did the player put
that block or item there themselves?** It is the reason place-then-break pays nothing.

## Why it is the LIBRARY's

Every reader has to get the SAME answer. This library's own break and pickup producers ask it, a
consumer's XP path asks it, a statistics counter asks it - and two ledgers would be two verdicts on
one break, so a server could hand out quest progress for a block it refused XP for. One authority
removes the disagreement structurally rather than by everybody remembering to agree.

## The pieces

| file | what it is |
|---|---|
| [`PlacedBlockLedger`](PlacedBlockLedger.java) | the only reader-facing surface: `trackPlacement` / `consumePlacement` / `isPlaced` for positions, `trackPlacedItem` / `consumePlacedItem` for item ids, plus the policy and the item-half sweep |
| [`PlacedBlockSection`](PlacedBlockSection.java) | where a BLOCK's answer is kept: one bit per block on the block's own chunk section, a plugin-registered `Component<ChunkStore>` |
| [`PlacedBlockRecorder`](PlacedBlockRecorder.java) | the single ECS `PlaceBlockEvent` system that WRITES it, registered from [`PlacedBlockBootstrap`](PlacedBlockBootstrap.java) at library setup |
| [`PlacedBlockBootstrap`](PlacedBlockBootstrap.java) | the setup phase (`setupPlacedBlockLedger`, called once from the root's `setup()`): the chunk component FIRST, then the recorder, then the legacy-file retirement, each under its own guard |

## Rules that bite

- **A consumer READS; it never records.** One recorder exists, in this library. A second one would
  raise the placed-item count once per installed mod, and a player who placed one block would then
  be refused their next several pickups of that item.
- **A block's record lives on its CHUNK; placed ITEMS are a COUNT.** An item lying on the ground has
  no coordinates to remember, so the item half remembers "this player put down N of these" and
  spends one per pickup. A caller with no world resolved must treat that as NOT placed - the same
  answer an unknown position gives - never pass a null world in.
- **ONE moment costs ONE row however many readers it has.** A single native break or pickup is read
  by several ECS systems in an order nobody specifies, and the first reader must neither take the
  answer away from the rest nor let the moment be charged twice. Spending a BLOCK's mark also
  remembers the position for `READ_GRACE_MS`, so whoever reads the same break second is told the
  same thing; without that the second reader would pay out on exactly the exploit the first refused.
  A placed ITEM has no position, so the reader NAMES the moment instead:
  `consumePlacedItem(uuid, itemId, momentKey)`, where the key is anything stable for that one event
  (the picked-up stack's own identity). Two readers with the same key inside the window are told the
  same answer for free.
- **`consumePlacement` is the break-time read and it SPENDS the mark; `isPlaced` is the read for a
  caller that is only looking.** Asking the consuming one to observe the ledger changes what it is
  observing, which is why the non-consuming door exists at all.
- **`Policy` is three INDEPENDENT knobs read LIVE** (`enabled` / `guardsPlacementsBy` /
  `itemExpireMinutes`), so a consumer whose own owner config already carries them installs one
  policy at setup and a reload moves them with nothing re-pushed.
- **Nobody earns from a placement, whoever breaks it.** Anything narrower is a two-player version of
  the same exploit, with one player standing ore up for another to mine. A placement that SHOULD pay
  is settled when the block goes down, by not recording it (see the filters below), never by
  softening the read.
- **The filters that decide whether a placement counts.**
  `PlacedBlockRecorder.placementCounts(cancelled, itemId, gameMode)` refuses a cancelled
  placement (nothing was put down), a null/blank/`Empty` item (nothing was in hand), and a
  CREATIVE-mode placement (an admin walling in an ore vein for survival players is the opposite
  of the exploit, and the block carries no signal at break time about who put it there). The
  library's own `PLACE_BLOCK` producer calls that same method rather than re-reading the event,
  so what is remembered as placed and what is produced as a moment can never disagree - a
  consumer that needs the same question answered calls the predicate, it never rewrites the
  filters. Beside it, `Policy.guardsPlacementsBy(placer)` lets the consumer exempt a builder
  working in SURVIVAL (typically by permission); that one only decides whether the placement is
  remembered, so an exempt builder still earns whatever placing is worth.

## Where a placement is kept

On the chunk section holding it, as one bit per block: `PlacedBlockSection`, registered on the
chunk-store registry at setup (BEFORE any world loads) and laid out like the engine's own
`BlockPhysics` - a lazily allocated byte array behind a versioned codec with a single
`Codec.BYTE_ARRAY` key, plus a leading flag byte so a section holding nothing costs one byte rather
than a whole array. The array is released again when its last mark is spent.

**This is why there is no file and no timer.** The engine's own chunk save carries the marks, a
lookup is an array index, only loaded chunks cost memory, and nothing is ever scanned, snapshotted
or rewritten on a beat - which is exactly what the JSON ledger it replaced had to do, at a cost that
grew with every placement the server had ever seen.

Two things are deliberately NOT persisted: placed ITEMS (forgotten in minutes, and a restart takes
longer than that, so saving them would only restore stale rows) and the grace-window positions (they
live for a second). A registration failure leaves the component type unset, and every read then
answers "not placed" - the wrong answer, but the safe one: the alternative refuses every break on
the server.

`PlacedBlockLedger.retireLegacyFile()` renames a leftover `mods/ziggfreedcommon/placed-blocks.json`
aside with one notice. Its rows are not carried across: a saved position cannot be put back onto a
chunk that is not loaded, and holding the whole file in memory until enough chunks arrived would
keep exactly the cost this layout removes.
