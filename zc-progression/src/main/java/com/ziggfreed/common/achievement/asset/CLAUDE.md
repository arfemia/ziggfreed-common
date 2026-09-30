# achievement/asset/

- `Criteria` merges per criterion key under `Parent`: a child retunes one criterion by its key and keeps the rest.
- Asset basenames must be unique across the whole store: the engine keys files by name before the fold, and a `_`-marked folder only namespaces the ids of differently named files.
- A milestone's identity is its `Threshold`: two files naming one number are one rung, and a file with no threshold is dropped. Category and milestone ids key off the file name, with `NestedAssetId` deliberately not wired.
- A field both engines share goes in `progress/asset` or `progress/gate`; a field only achievements have goes in this package's own codec.
- A registered objective kind that is not producible is an error (`UNPRODUCIBLE_KIND`); an unknown one stays a warning (`UNKNOWN_KIND`).
