# world/

## Where (world targeting)

- `Where` (`WorldSelector`: `{Match, GameplayConfig, ExcludeMatch}`) is the one spelling of "which worlds?" in the family. A bare word under `Match` is an exact world name, so `["default"]` reaches that world alone; `ExcludeMatch` only filters the positive axes, so an exclude-only `Where` matches nothing.
- `WorldSelector` carries no defaults (each read site applies its own) and answers a `MatchRank` or null, never a boolean. Under `Parent` an authored `Where` replaces the parent's whole: its axes are OR'd alternatives, so a merge would broaden the child.
- `MatchRank` sorts `GameplayConfig` exact > exact name > the partial pattern with the longest literal core > bare `*`, and a tie keeps authoring order. It is zc-core's `NameMatchRank` plus the `GameplayConfig` rung, so never keep a private ladder.
- An instance world is named `instance-<name>-<uuid>`: only the contains form (`*Name*`) reaches it by name, so prefer targeting an instance by the `GameplayConfig` it runs.
- An empty `WorldIdentity.loadedWorlds()` means cannot tell. `WhereValidator.validateAgainstWorlds` therefore runs only in a late audit, and reports a `GameplayConfig`-only selector at INFO, since an instance is usually not running.

## Blocks

- The native place-block event fires before the engine refuses a placement, so anything paying per placement asks `BuildPermission` first; cannot tell reads as allowed.
- `BlockOps` is the one block read and write: it never loads a chunk, null means cannot tell, air reads as `"Empty"`, and identity reads (tags, resource types) go through the block's item.
- `record/BlockRecordSection` registers before any world loads. Its mutators flag the section for saving; a caller mutating a fetched record in place calls `markDirty`; `clone` deep-copies so an IO-thread save never tears.
- `pattern/BlockPattern` has exactly one anchor cell, and one positive quarter-turn maps `(x, y, z)` to `(z, y, -x)`, the engine's `Rotation.Ninety`, so a matched orientation carries straight into a block write.
- `stash/BlockStashes` is pure storage on world game time (an outage advances nothing); item insertion order, oldest first, is load-bearing.
- Placed-block ledger rules live in `placed/CLAUDE.md`.

## Terrain

- `SurfaceProbe` stops on trees decorated after worldgen unless handed the foliage keys from `BlockTypeLists.keys("TreeWoodAndLeaves", "AllScatter")`; `SpawnPlacement` takes a seed and never calls `Math.random`.
