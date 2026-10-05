# world/

## Where (world targeting)

- `Where` (`WorldSelector`: `{Match, GameplayConfig, ExcludeMatch}`) is the one spelling of "which worlds?" in the family. A bare word under `Match` is an exact world name, so `["default"]` reaches that world alone; `ExcludeMatch` only filters the positive axes, so an exclude-only `Where` matches nothing.
- `WorldSelector` carries no defaults (each read site applies its own) and answers a `MatchRank` or null, never a boolean. Under `Parent` an authored `Where` replaces the parent's whole: its axes are OR'd alternatives, so a merge would broaden the child.
- `MatchRank` sorts `GameplayConfig` exact > exact name > the partial pattern with the longest literal core > bare `*`, and a tie keeps authoring order. It is zc-core's `NameMatchRank` plus the `GameplayConfig` rung, so never keep a private ladder.
- An instance world is named `instance-<name>-<uuid>`: only the contains form (`*Name*`) reaches it by name, so prefer targeting an instance by the `GameplayConfig` it runs.
- An empty `WorldIdentity.loadedWorlds()` means cannot tell. `WhereValidator.validateAgainstWorlds` therefore runs only in a late audit, and reports a `GameplayConfig`-only selector at INFO, since an instance is usually not running.

## Blocks

- The native place-block event fires before the engine refuses a placement, so anything paying per placement asks `BuildPermission` first; cannot tell reads as allowed.
- `BlockOps` is the block read and write a consumer reaches for: it never loads a chunk, null means cannot tell, air reads as `"Empty"`, and identity reads (tags, resource types) go through the block's item. Under it sits zc-core's `world/SectionBlockCursor`, the raw block-id read for a walk over many cells (`SurfaceProbe`) and for a module with no zc-world edge (zc-cast's `BlockRaystep`, zc-presentation's `BlockStateSound`): it never loads a chunk either, keeps the last section it resolved, and reads `BlockType.EMPTY_ID` for air and for a cell it cannot see.
- Never read a block through a `World` or `WorldChunk` accessor (`getBlock(x, y, z)`, `getBlockType(x, y, z)`), nor a cell's environment through a column accessor (`getEnvironment(x, y, z)`): Update 7 deletes them, and the root `BlockAccessorHygieneTest` fails on either. `BuildPermission` reads the environment off the cell's chunk section (`EnvironmentSection.get`), the read the engine's own placement check makes.
- `record/BlockRecordSection` registers before any world loads. Its mutators flag the section for saving; a caller mutating a fetched record in place calls `markDirty`; `clone` deep-copies so an IO-thread save never tears.
- `pattern/BlockPattern` has exactly one anchor cell, and one positive quarter-turn maps `(x, y, z)` to `(z, y, -x)`, the engine's `Rotation.Ninety`, so a matched orientation carries straight into a block write.
- `stash/BlockStashes` is pure storage on world game time (an outage advances nothing); item insertion order, oldest first, is load-bearing.
- Placed-block ledger rules live in `placed/CLAUDE.md`.

## Sections and entities

- An entity stays in the world only in a TICKING chunk section. On Update 7 a section loads asleep whatever its column does, and the column's ticking flag no longer reaches it: an entity added into a sleeping section is parked into that section on the spot (the add answers null and the caller's post-spawn never runs) and comes back as a load once a player's hot sphere or a `SET_TICKING` section request wakes it. Read and wake a section through `TickingSections`: `stateAt` reads (`TICKING`, `PARKING`, `ABSENT`), `ensureTicking` wakes a section in memory on the world thread and never loads, `whenTicking` runs a step once the section ticks, `holdTicking` keeps a ticking one awake and never wakes. Never decide from a column's `ChunkFlag.TICKING` or a column request's `SET_TICKING`. A wake brings the section's parked entities back first, so a caller that may have parked its own copy there adopts it before adding another; never wake inside a system's processing window. The root `SectionTickingHygieneTest` fails a main source reading `ChunkFlag.TICKING` or passing `SET_TICKING` outside `TickingSections` (bar `NpcPlacementService`'s two 2.2.0 column methods, kept for linkage).

## Terrain

- `SurfaceProbe` stops on trees decorated after worldgen unless handed the foliage keys from `BlockTypeLists.keys("TreeWoodAndLeaves", "AllScatter")`; `SpawnPlacement` takes a seed and never calls `Math.random`. Neither loads a chunk: a column not in memory answers the caller's fallback, so a caller probing ground no player is near loads it first.
