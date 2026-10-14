# encounter/types/

- A `ZigGrant`'s `Loot` decodes once, at script load, through `BuilderActionZigGrant.decodeLoot` in the asset context the engine hands the script's builders, never a plain `ExtraInfo`: `LootRef`'s `Lootables` leaf is a contained-asset codec that throws outside an asset context. The encounter audit reads it through the same decode, and one that cannot be read is `ENCOUNTER_GRANT_LOOT_UNREADABLE`. A whole table written inline there is `ENCOUNTER_GRANT_INLINE_TABLE`: the engine's generated id is the script id plus the key stack, and a script's builders push no list position, so two can collide.
- Every registered action answers finished on every path: to the engine a `false` means still running, and a blocking action list waits on it (`ActionsAlwaysFinishTest`).
- Encounter `Type` builder keys are flat and array-ranged (`Count: [min, max]`), the native builder vocabulary, unlike the nested asset codecs.
