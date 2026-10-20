# effect/grid/ - a per-entity number through an asset-only effect field

- A native effect field that only reads assets (`DamageResistance`, `OutgoingDamage`, the movement multipliers) carries a per-entity number through a `RuntimeEffectGrid`: one shared asset per bucket, written the first time anyone needs it through `AssetStoreWriter`, never one per entity (each joins the catalogue every client syncs), so pick a bucket rule whose count stays bounded.
- A grid owns every active effect under its prefix (`reconcile` sweeps the unwanted ones); an apply inside a tick can answer `PENDING` and re-runs from `onLanded` (wrap with `onWorldThread`).
- `reconcile` is make-before-break: a channel's new bucket goes on first, and its old one comes off only once the new one is on (a bucket still landing keeps the old one), so a first-ever bucket never leaves the entity with nothing.
- `EffectBodies.rootCauses` is the key set for an `OutgoingDamage` body, which matches a hit's whole Inherits chain; a `DamageResistance` body keys exact causes, since the engine looks a hit's cause up exactly.
