# achievement/asset/

- `Criteria` merges per criterion key under `Parent`: a child retunes one criterion by its key and keeps the rest.
- Asset basenames must be unique across the whole store: the engine keys files by name before the fold, and a `_`-marked folder only namespaces the ids of differently named files.
- A milestone's identity is its `Threshold`: two files naming one number are one rung, and a file with no threshold is dropped. Category and milestone ids key off the file name, with `NestedAssetId` deliberately not wired.
- A field both engines share goes in `progress/asset` or `progress/gate`; a field only achievements have goes in this package's own codec.
- A registered objective kind that is not producible is an error (`UNPRODUCIBLE_KIND`); an unknown one stays a warning (`UNKNOWN_KIND`).
- `Listing.Feat` and `Listing.LegacySince` are listing leaves: a feat changes only where it is listed, never whether its points count (`Scoring.CountsTowardTotal` alone decides).
- `Occurrence` mints at `resolve`, through zc-core's occurrence slot (`OccurrenceReader` over `Occurrences.source()`, read afresh on every question), from the event's first year through next year (`OccurrenceMinting`, at most `MAX_YEARS`): the year questions look past the owner's switches, so a switched-off event keeps its copies and only their circulation closes; the file itself never reaches a pool, a copy whose id an authored file already uses yields to it (`MINTED_ID_CLASH`), `@year` answers a `TextArgs` entry or a reward parameter's whole value, and an explicit child of the same event reads as the same year's copy.
- `MetaSelector` resolves after minting (`MetaSelection`): explicit `MetaChildren` first, then every pick by id; it never picks the capstone or any capstone, only inside the capstone's own occurrence, and an empty selector picks nothing (`EMPTY_META_SELECTOR`).
- The fold lifts a top-level plain `hytale:mod_installed` condition onto `available` (`FeatureLift.liftModPresence`), so an achievement gated on a companion mod is out of circulation where that mod is missing, a yearly copy included; a feature condition stays in `Requires`, a refusal the self-heal re-reads.
