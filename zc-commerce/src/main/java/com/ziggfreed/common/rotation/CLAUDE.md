# rotation/

- A rotation is a pure function of (poolId, period, seed): never add a stored current set, a schedule or a cleanup sweep.
- An unknown selection type draws nothing, never the default. A weight of zero or less reads as one; exclusion is the slot filter's job.
- A reroll replacement excludes everything on show (`excludeAll`) plus whatever already sat at that position this period, and a stale override is dropped.
- These are runtime values: authoring is `commerce/asset`, and `commerce/fold/CommerceFold` crosses over following the authored leaf, so an unauthored cadence never turns over.
