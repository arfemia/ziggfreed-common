# world/placed/ - the placed-block ledger

- A consumer only reads `PlacedBlockLedger`; the library's `PlacedBlockRecorder` is the one writer. A second recorder over-counts placed items once per installed mod.
- Ask `PlacedBlockRecorder.placementCounts` whether a placement counts; never re-implement its filters (cancelled, empty hand, creative mode, `world/BuildPermission`).
- `consumePlacement` spends the mark at break time; `isPlaced` only observes. An item read passes a `momentKey` stable for the one event, so several readers of it spend one copy.
- Exempt a builder through `Policy.guardsPlacementsBy`, which leaves the placement unrecorded, never by softening the read: nobody earns from a recorded placement, whoever breaks it.
- With no world resolved, treat a block as not placed; never pass a null world.
