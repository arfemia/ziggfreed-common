# world/placed/ - the placed-block ledger

- A consumer only reads `PlacedBlockLedger`; the library's `PlacedBlockRecorder` is the one writer. A second recorder over-counts placed items once per installed mod.
- Ask `PlacedBlockRecorder.placementCounts` whether a placement counts; never re-implement its filters (cancelled, empty hand, creative mode, `world/BuildPermission`).
- `consumePlacement` spends the mark at break time; `isPlaced` only observes. An item read passes a `momentKey` stable for the one event, so several readers of it spend one copy.
- Exempt a builder through `Policy.guardsPlacementsBy`, which leaves the placement unrecorded, never by softening the read: nobody earns from a recorded placement, whoever breaks it.
- With no world resolved, treat a block as not placed; never pass a null world.
- `placedBy` names a marked block's placer (a section's `PlacerPalette`: one id while one player built there, a packed index a block past that), for a rule that takes only a player's own blocks; the guard never reads it.
- The spared mark (`markSpared`, `isSpared`, `clearSpared`) marks a placement the library's thrift step kept the item of, so its break drops nothing, and keeps the spared block's own item id (`sparedIdentity`, shared by its states and rotations). Only `gather/ThriftSystem` sets it; any other player placement on the cell and every break with no breaker (`gather/ThriftDropSystem.EnvironmentBreak`) clear it, and `isSpared` takes the block now in the cell and forgets a mark naming another one (physics and a mod's direct write fire nothing).
