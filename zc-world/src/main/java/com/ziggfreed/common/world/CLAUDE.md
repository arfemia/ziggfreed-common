# world/ - world targeting + worldgen / terrain query primitives

Router for `com.ziggfreed.common.world`. Mod-agnostic helpers for reading the world, plus the ONE
authority every consumer's "which worlds does this apply to?" field resolves through.

## World targeting (the `Where` layer)

> **A world is named by what it is CALLED or by the gameplay config it runs, and by nothing in
> between.** There is no third vocabulary to look up in a second file: a `Where` carries the
> patterns themselves, so what a file targets is readable on its own page, and every rule sorts on
> the one specificity ladder server owners already know from raw patterns.

- **[`WorldSelector`](WorldSelector.java)** - the embeddable nested-group `BuilderCodec` ANY consumer asset carries as a field under the key `Where`: `{Match, GameplayConfig, ExcludeMatch}`. **This is the ONE spelling of "which worlds?" in the library** - an NPC placement's `Where`, a world rule's `Where`, a mob-scaling world file's `Where` and a dialogue `World` condition's `Where` are all this group, so an author learns it once and a new axis reaches every surface at the same moment. **The codec carries NO defaults - absent stays null** (read sites genuinely differ, so each applies its own; one codec with two invisible Java-side defaults would be a rework). **A bare word under `Match` is an EXACT world name**, so `["default"]` reaches the world called `default` and no other - the shipped files that target the main world rely on that, and a contains reading would place their content inside every instance carrying the word. `ExcludeMatch` takes the SAME pattern grammar as `Match` and is a FILTER over the positive axes, never a complement: an `ExcludeMatch`-only `Where` matches **nothing**. `match(World)` is the engine-facing convenience (try-guarded, world-thread); `match(worldName, gameplayConfig)` is the pure decision core. Returns a `MatchRank` or `null`, **never a bare boolean** - consumers need precedence. **Under native `Parent` inheritance an authored `Where` REPLACES the parent's selector wholesale** (the leaves deliberately register NO inherit function): a `Where` is ONE predicate whose positive axes are OR'd alternatives, so a child's `GameplayConfig` inheriting the parent's `Match` underneath it would silently broaden the child to worlds nobody authored it for (the shipped bug: a temple-only child placement inherited `Match:["default"]` and stood a duplicate NPC at the main world's spawn). A child that omits `Where` still inherits the parent's whole selector wherever the OWNING asset registers its `Where` field with an inherit function (the NPC placement asset does); a child that authors one and wants the parent's `ExcludeMatch` restates it. `NpcPlacementAssetCodecTest` pins both halves.
  - **The different-main-world story is each asset's own owner layer.** A file that should follow a differently-named main world is re-authored by id from a pack, or overridden through that asset type's owner layer (`mods/ziggfreedcommon/npc-placements.json`, `mods/mmoskilltree/world-rules.json`). It is a handful of files, and each states what it targets where a reader can see it.
- **[`MatchRank`](MatchRank.java)** - the specificity ladder, most specific first: **band 0** `GameplayConfig` exact (author-controlled, uuid-free, "this world IS the Forgotten Temple"), **band 1** exact world name, **band 2** partial (prefix/suffix/contains) with the LONGER literal core winning and anchoring only breaking a tie, **band 3** bare `*`. Comparison is total and deterministic (band ASC, core length DESC, kind ordinal ASC; non-partial bands normalize both tie-breakers to 0) and natural order is most-specific-first. **Core length dominates kind inside band 2** because the contains form is the only one that reaches an instance world, so ranking by anchoring would let a vague `inst*` beat a precise `*Forgotten_Temple*`. `moreSpecific(current, candidate)` folds candidates keeping the FIRST of two equal ranks, so authoring order decides what the ladder cannot. **It IS the shared [`match/NameMatchRank`](../../../../../../../../zc-core/src/main/java/com/ziggfreed/common/match/CLAUDE.md) ladder plus ONE world-specific rung**, the `GameplayConfig` band above every name pattern: the bands, the comparison and the first-wins-on-tie fold all live in the shared record, so a world rule and any other name-matched rule in the library can never sort differently. `band()`/`coreLength()`/`kindOrdinal()` read through to it.
- **[`WorldIdentity`](WorldIdentity.java)** - `loadedWorlds()`, the guarded enumeration of every world the server currently has (over `Universe.get().getWorlds()`), as `(name, gameplayConfig)` pairs. It exists so the describes-a-real-world audit has ONE engine-touching source to ask and [`WhereValidator`](WhereValidator.java) itself stays pure. **An EMPTY answer always means "cannot tell", never "nothing is loaded"** - it is what a unit JVM, a pre-boot call and a failed read all return, and a caller treating it as an answer would report a finding against every world-targeted file on the server. Nothing is cached: a name and a config key are two field reads, and an instance roster changes as instances come and go, so a cache would only be a way to answer with a world that has already been torn down.
- **[`WhereValidator`](WhereValidator.java)** - the findings that are otherwise SILENT (content bound to an unmatchable `Where` just never appears), reported as shared [`validation.Finding`](../../../../../../../../zc-core/src/main/java/com/ziggfreed/common/validation/CLAUDE.md) values under domain `where`. Two entry points, because there are two questions:
  - **`validateSelector`** asks what the group says about ITSELF - an `ExcludeMatch`-only `Where` (`EXCLUDE_ONLY`, an ERROR: it can never match), and blank entries (`BLANK_ENTRY`). Its whole answer is in hand the moment the owning file decodes, so it is safe on every layer fold.
  - **`validateAgainstWorlds`** reports a `Where` that describes NONE of the loaded worlds (`MATCHES_NO_LOADED_WORLD`). That is the shape a renamed main world produces: everything is well formed, every pattern is spelled right, and the content simply never appears. **Severity depends on how the selector names its worlds**: a selector whose only positive axis is `GameplayConfig` reports at INFO, because that is exactly how content aims at an INSTANCE world and an instance is not running most of the time by design - warning about it every boot would train an owner to skip the line that does mean something; every other shape is a WARNING. An EMPTY world list means "cannot tell" and reports nothing - so this half belongs to a LATE audit, once the universe is up, never to a fold.
- **[`WorldNameMatcher`](WorldNameMatcher.java)** - the WORLD flavour of the shared name-pattern grammar. The grammar itself lives once in [`match/NamePattern`](../../../../../../../../zc-core/src/main/java/com/ziggfreed/common/match/CLAUDE.md) (exact name / trailing-`*` prefix (`Foo*`) / leading-`*` suffix (`*Foo`) / leading+trailing contains (`*Foo*`) / bare `*`, parsed into a `Kind` plus a lower-cased literal `core()`), so a world-targeting field and a loot trigger key can never disagree about what a pattern means. `Pattern` IS a `NamePattern` - it subclasses it purely so `Pattern.parse` keeps answering with the world-side type its callers already speak - and `kind()` answers with the SHARED `NamePattern.Kind` vocabulary. The CONTAINS form is the ONLY one that catches an `instance-`-prefixed AND randomly-suffixed instance world (e.g. `instance-KweebecNightmare_Chase-<uuid>`); a bare trailing-`*` prefix cannot. Pure logic, zero engine coupling. **It parses and scores; it never SELECTS** - picking a winner among candidates is `WorldSelector` + `MatchRank`, so no consumer keeps a private ladder. `MatchRank` is built straight off `Pattern.kind()` + `coreLength()`, which is what keeps a validator reasoning about a pattern and the runtime matching it from ever disagreeing. `WorldNameMatcherTest` asserts the whole ladder through that one selection path.

## The placed-block ledger

Nested package with [its own router](placed/CLAUDE.md); read that before touching any of its four classes.

- **[`placed/PlacedBlockLedger`](placed/PlacedBlockLedger.java)** - the ONE answer to "did the player put that block or item there themselves?", and the reason place-then-break pays nothing. A BLOCK's mark is kept on the block's own chunk section, so the engine's own chunk save carries it; placed ITEMS are counted in memory rather than positioned (an item on the ground has no coordinates to remember) and are not persisted at all, since they are forgotten in minutes and a restart takes longer than that. There is no ledger file: a leftover `mods/ziggfreedcommon/placed-blocks.json` is renamed aside once by `retireLegacyFile()` and never read. **It lives in the library rather than in a consumer because every reader has to get the same answer**: zc-objectives' break and pickup producers, a consumer's XP path and a statistics counter all ask it, and two ledgers would be two verdicts on one break. `Policy` is three INDEPENDENT knobs read LIVE (`enabled` / `guardsPlacementsBy` / `itemExpireMinutes`), so a consumer whose own owner config already carries them installs one policy at setup and a reload moves them with nothing re-pushed. **Fairness**: nobody earns from a placement, whoever breaks it, and no setting narrows that refusal to the placer alone. A placement that SHOULD pay is settled when the block goes down: `Policy.guardsPlacementsBy(placer)` answering false leaves that player's placements unrecorded, which is how a consumer exempts a builder working in SURVIVAL (a Creative-mode placement is already exempt without asking). **One moment costs one row, however many readers it has**: ONE native break or pickup is read by several ECS systems in an order nobody specifies, and the first reader must neither take the answer away from the rest nor let the moment be charged twice. A BLOCK is keyed by position, so a consumed row simply keeps answering for `READ_GRACE_MS`. A placed ITEM is a COUNT, so the reader names the moment - `consumePlacedItem(uuid, itemId, momentKey)`, where the momentKey is anything stable for that one event (the picked-up stack's own identity) - and a second reader with the same key inside the window is told the same answer for free. Without it, two installed readers would spend two of the player's placed copies per pickup and hand back every other one.
- **[`placed/PlacedBlockRecorder`](placed/PlacedBlockRecorder.java)** - the single ECS `PlaceBlockEvent` system that WRITES the ledger, registered from `placed/PlacedBlockBootstrap` at library setup. A consumer reads; it never records, or the placed-item count would be raised once per installed mod. A CREATIVE-mode placement is deliberately not recorded (an admin walling in an ore vein for survival players is the opposite of the exploit, and the block carries no signal at break time about who put it there).
- **[`placed/PlacedBlockSection`](placed/PlacedBlockSection.java)** - where a BLOCK's answer is actually kept: one bit per block on the block's own chunk section, a plugin-registered `Component<ChunkStore>` (`REGISTRY_ID` `ZigPlacedBlocks`) whose array is allocated only once a mark lands and released again when the last one is spent. No file and no timer: a lookup is an array index, only loaded chunks cost memory, and a failed registration answers "not placed" rather than refusing every break.
- **[`placed/PlacedBlockBootstrap`](placed/PlacedBlockBootstrap.java)** - the `setupPlacedBlockLedger` phase called once from the root's `setup()`: the chunk component FIRST (before any world loads), then the recorder, then the legacy-file retirement, each under its own guard so one failure cannot silently skip the next.

## Block IO, patterns + per-block records

- **[`BlockOps`](BlockOps.java)** - single-block read/write at world coordinates over the engine's
  CURRENT block surface, the one place the library touches raw block IO (a consumer probing a
  neighbour cell or swapping a block calls this, never the deprecated `World.getBlock` /
  `WorldChunk` accessor family). `blockItemIdAt(chunkStore|store, x, y, z)` resolves the section
  (`ChunkStore.getChunkSectionReferenceAtBlock`, a map lookup that NEVER loads a chunk), reads
  `BlockSection`, and answers the block ITEM id - air answers the engine's own `"Empty"` key, null
  strictly means "cannot tell" (unloaded section / failure), so a matcher can tell an empty cell
  from an unknowable one. `rotationIndexAt(chunkStore|store, x, y, z)` reads the stored rotation
  index at a position through the same section resolution (rotation lives in its own storage layer
  beside the block ids; null = cannot tell), feeding `RotationTuple.get` and `setBlock`'s rotation
  parameter. `setBlock(chunkStore, x, y, z, blockItemId[, rotationIndex])` runs the
  engine's full `BlockOperations.setBlock` (heightmap, particles, block-entity swap, lighting,
  fillers, physics), the rotation parameter letting a caller that replaces a block preserve the
  rotation it read. `setInteractionState(chunkStore, x, y, z, state, force)` moves a block to one
  of its OWN authored `State` siblings (an on/off pair), keeping rotation. Beside the IO sit the
  id-based IDENTITY reads, so every consumer classifying blocks resolves identity one way:
  `baseItemIdOf(id)` (a state-variant block reads back under its own generated id; this answers
  the block that authored the state family, in one hop via the variant's containing block type -
  deliberately NOT the asset parent key, which an item-level `Parent` also fills and which would
  mis-fold an ordinary inheriting block onto its template; a base/unknown id answers ITSELF, never
  null), `itemOf(id)` (the containing `Item` asset via `BlockType.getItem()`; a block type can
  only be defined inside an item, and a state variant shares its base's item; the engine's few
  synthetic block types answer null), `rawTagsOf(id)` / `resourceTypeIdsOf(id)` (BOTH read the
  ITEM - `item.getData().getRawTags()` / `item.getResourceTypes()` - because the block type's own
  tag map exists but is empty in practice, so a block-side read would see nothing; ids in authored
  order, empty list = "has none", null = "cannot resolve"). Fail-closed + world-thread throughout
  (the identity reads are asset-map lookups, safe wherever assets are loaded).
- **[`record/BlockRecordSection<T>`](record/BlockRecordSection.java)** - the GENERIC per-block
  keyed-record `Component<ChunkStore>`: one payload value of the caller's own `BuilderCodec` type
  per block position, kept on the section and saved/loaded with the chunk (the keyed, sparse
  counterpart to `placed/PlacedBlockSection`'s one-bit array; the map-keyed-by-section-local-index
  layout mirrors the engine's own `BlockComponentSection` `"Blocks"` shape). `register(registry,
  registryId, payloadCodec)` at plugin setup (BEFORE any world loads) hands back a typed `Handle`
  with the position-facing accessors (`get` / `ensureAndGet` / `remove` / `forEach` / `count` /
  `markDirty`); a failed registration degrades every read to "nothing was ever stored". One class
  backs any number of registrations, each under its own registry id. **Two persistence facts are
  wired in, read out of the engine's save path**: (1) the engine saves a section only when its
  `ChunkSection` is flagged (`markNeedsSaving`) and nothing watches a plugin component, so the
  mutating accessors flag it themselves and a caller that mutates a FETCHED record in place owes
  one `markDirty` (a record write, unlike a block place/break, has no engine block change raising
  the flag for it); (2) the save snapshots on the world thread (`clone`) but serializes on an IO
  thread, so `clone` deep-copies every record through the payload codec - a mid-save mutation can
  never tear the bytes being written. An empty section serializes to the bare version marker.
  World-thread only.
- **[`pattern/`](pattern/BlockPattern.java)** - generic structure-pattern matching: does the world
  around a candidate position hold this shape? The PuppetNav split, applied to shape matching: a
  pure core over functional seams, one thin live wiring.
  - **`PatternCell<P>`** - one cell: an integer offset in whole blocks plus an OPAQUE caller
    payload `P`. What a cell accepts is entirely the payload's meaning to the caller; zc never
    inspects it and holds ZERO matching vocabulary (no ids, no tags, no acceptance rules).
  - **`BlockPattern<P>.compile(cells, anchorIndex, rotate, mirror)`** - the compiled pattern.
    Exactly ONE anchor cell, named by index; compile re-bases every authored offset so the anchor
    sits at the origin (order and the anchor INDEX are preserved), and every rotation/mirror
    pivots on it, so a variant's match position IS its anchor position. Expands up to 8
    precomputed **`PatternVariant`s**: 4 yaw quarter-turns, each optionally X-mirrored (mirror
    negates authored X FIRST, then the turns). **Rotation convention**: one positive quarter-turn
    maps `(x, y, z)` to `(z, y, -x)` - the engine's own yaw `Ninety` vector turn - so
    `yawQuarterTurns` (0..3) lines up with the engine `Rotation` ordinals and a matched
    orientation carries straight into a block write. NO variant dedup (payloads are opaque, so
    symmetry cannot be proven here; a fully symmetric pattern compiles with `rotate` false).
    `boundingRadius()` = the largest absolute offset component over all cells (Chebyshev,
    identical for every orientation), for proximity pruning.
  - **Matching** - `variant.matchAt(ax, ay, az, reader, predicate)` walks the cells in authored
    order through two seams, short-circuiting on the first fail: **`BlockReader`** `(x, y, z) ->
    block item id or null`, where null strictly means "cannot tell" and ALWAYS fails the cell (an
    unloaded section never matches and is never loaded; air arrives as the engine's `"Empty"`
    key, so must-be-air cells are testable), and **`CellPredicate<P>`** `(payload, blockItemId) ->
    boolean`, the caller's whole acceptance rule. `anchorFromCell(cellIndex, x, y, z)` derives the
    implied anchor from any known cell position; `matchFromCell(...)` does derive + walk + wrap in
    one call and answers a **`PatternMatch<P>`** (pattern, variantIndex, anchor position,
    yawQuarterTurns, mirrored) or null. `BlockReader.over(chunkStore)` is the ONE live wiring
    (delegates to `BlockOps.blockItemIdAt`; world-thread only).
  - **`PatternIndex<P>`** - block item id -> caller-registered `(pattern, variantIndex,
    cellIndex)` candidates. The CALLER decides which cells are indexable (exact-id cells); the
    index stores and answers, exact-case keys, duplicates ignored, plus `maxBoundingRadius()`
    over every registered pattern for pending-candidate proximity checks. Not thread-safe; the
    caller confines it.
- **[`stash/`](stash/BlockStashes.java)** - the first record-section consumer: what one block
  position is HOLDING for players, persisted with the chunk. `BlockStash` (`Owner`, `Piles` by
  pile key, `ProgressGameTime`/`LastGameTime` - both WORLD GAME TIME, never wall clock, so a
  server outage advances them by zero - and an opaque consumer `Tag`) over `StashPile` (`Owner`,
  `Items` counted by item id in INSERTION order - oldest-first drain order is load-bearing -
  `Unique`, one whole `ItemStack` through the engine's own item codec so per-stack metadata
  round-trips byte-identically, behind a `codec/DeferredCodec` so the class stays loadable without
  the engine's item statics, and `PendingCycles`, accrued counts for work settled while nobody was
  present). **Pure storage**: no accept rule, no consume policy, no ownership enforcement - the
  consumer rules who may take, the stash only records who placed. `BlockStashes` is the ONE shared
  store every consumer reads (`stashAt` / `ensureStashAt` / `removeStashAt` / `forEachInSection` /
  `countInSection` / `markDirty`; registry id `ZigBlockStash`), registered once by
  `stash/BlockStashBootstrap` from the root's `setup()`. Whoever mutates a fetched stash in place
  calls `markDirty` once when done.

## Terrain / placement helpers

- **[`SurfaceProbe`](SurfaceProbe.java)** - `topSolidY(world, x, z, fallback)` / `standableY(...)`: scan a column down for the top OPAQUE-solid block (skips air + `Opacity.Transparent` foliage/glass), mirroring the engine's `GeneratedBlockChunk.getHeight` but reading a live `World` via `World.getBlock(int,int,int)`. **World-thread only** (call inside `world.execute`); every read is try-guarded so an unloaded chunk degrades to the caller's fallback. Use it to floor-snap runtime-placed prefabs/entities onto procedural, uneven terrain instead of a hardcoded Y (e.g. Kweebec's `ArenaBuilder` snaps shrine/exit/gate/cave-shaft pastes to the rolling grove surface). **Skip-set overloads** (`topSolidY(... Set<String> skipBlockKeys)`, `standableY(... skip)`) ALSO scan past caller-given block keys: a RUNTIME paste runs AFTER worldgen has decorated trees onto the surface, so the plain probe stops on a tree trunk/leaf above the ground; pass the foliage keys (see `BlockTypeLists`) to reach the genuine floor. Worldgen's own snap dodges this (it runs against the terrain buffer BEFORE the prop/tree phase).
- **[`BlockTypeLists`](BlockTypeLists.java)** - `keys(String... listIds)`: resolves the engine's authored `BlockTypeList` assets (`TreeWoodAndLeaves`, `AllScatter`, ...) into a cached, unioned `Set<String>` of block keys. Asset-driven (new tree/scatter blocks follow the vanilla lists automatically); feeds `SurfaceProbe`'s foliage-skip set. Call at runtime (after asset registration), not at class-init; try-guarded so a missing list contributes nothing.
- **[`SpawnPlacement`](SpawnPlacement.java)** - floor-snapped runtime spawn positions on uneven terrain, each Y resolved via `SurfaceProbe.standableY`: `ringAround(world, cx, cz, radius, count, fallbackY)` (evenly-spaced angles on a ring), `nearPlayer(world, px, pz, minRadius, maxRadius, seed, fallbackY)` (a DETERMINISTIC-seeded random angle + radius in `[min,max]`; caller passes the `seed`, NO `Math.random`), `snapToSurface(world, x, z, fallbackY)` (thin `Vector3d` wrapper over the probe). Returns `org.joml.Vector3d`. **World-thread only** (it drives `SurfaceProbe`); each Y read is try-guarded so an unloaded chunk degrades to `fallbackY`. Generic: the caller supplies all geometry/seed/fallback, no ids. **Skip-set overloads** (`ringAround(... Set<String> skipBlockKeys)`, `nearPlayer(... skip)`, `snapToSurface(... skip)`) forward the foliage keys to `SurfaceProbe.standableY(... skip)` so a near-player/ring spawn in a treed grove snaps to the GROUND under runtime tree decoration instead of onto a trunk/leaf; the no-skip overloads delegate to these with `null` (no skips). Resolve the keys from `BlockTypeLists.keys("TreeWoodAndLeaves", "AllScatter")`.
- **[`WeightedPrefabPlacementAsset`](WeightedPrefabPlacementAsset.java)** + **[`WeightedPrefabPlacementConfig`](WeightedPrefabPlacementConfig.java)** - a pack-authorable weighted prefab-placement table (Pattern A, common-registered by `asset/FrameworkAssetRegistrar`, `Server/ZiggfreedCommon/PrefabPlacements/`): one file per placement (`PrefabKey`/`Role`/`X`/`Z`/`Weight`). The config (extends `AbstractKeyedAssetConfig`) ports the seeded selection as PURE helpers `select(seed, max, accept)` / `selectWeighted(seed, max, accept)` (caller supplies the seed + a keep-clear predicate). Lifted from kweebec's `StructurePlacementAsset` (path renamed `Placements` -> `PrefabPlacements`); consumed by Kweebec's `StructureCatalog`.
- **[`ForcedMusicService`](ForcedMusicService.java)** - `applyFor(store, ref, containerId)` / `clearFor(store, ref)`: force a `MusicContainer` music bed onto ONE player via the `UpdateForcedMusic` packet (id 151) - resolve+validate the id through `MusicContainer.getAssetMap().getIndex`, set the player's `ForcedMusicTracker` `currentContainerIndex`, AND push `UpdateForcedMusic` DIRECTLY through `PlayerRef.getPacketHandler().write` (the mechanism-independent send - does not rely on `ForcedMusicSystems.Tick` running in an instance world; on a good send it also sets `lastSentContainerIndex` so Tick will not duplicate). `applyFor` returns false on a bad / unresolved id or invalid ref (caller retries next tick, never latches index 0); `clearFor` sends index 0 to restore the default bed. **World-thread only** (Store/Ref reads); the packet write is thread-safe; fully try-guarded. Mod-agnostic: the candidate-id / tier policy stays in the consumer, which iterates its own roster and calls `applyFor` per player. Lifted from Kweebec's `MusicBedService` (mechanism only; the `DREAD_MUSIC_CANDIDATES`/tier ladder stayed in kweebec).
- **[`AtmosphereService`](AtmosphereService.java)** - `setDayTime(world, dayTimeFraction, pauseTime)` / `setForcedWeather(world, weatherId)` / `lock(world, dayTimeFraction, weatherId)`: control a world's time-of-day + forced weather. `setDayTime` sets `WorldTimeResource.setDayTime` (`0.0` = midnight / darkest, `0.5` = noon) + `WorldConfig.setGameTimePaused` + `markChanged`; `setForcedWeather` VALIDATES the id (`Weather.getAssetMap().getIndex != Integer.MIN_VALUE`, an unknown id blanks the sky - skipped) then sets BOTH `WeatherResource.setForcedWeather` + `WorldConfig.setForcedWeather` (a `null` id clears it); `lock` = `setDayTime(pause=true)` + `setForcedWeather` (the frozen-dark-midnight convenience). Every call self-hops via `world.execute` (safe from any thread); fully try-guarded. Mod-agnostic: the dark-weather candidate list + choice stay in the consumer. Lifted from Kweebec's `AtmosphereService` (mechanism only).
