# instance/reward/ - the mod-agnostic reward model + score-tiered loot tables

Router for `com.ziggfreed.common.instance.reward`. The reusable end-game reward layer a consumer
minigame/dungeon grants from: a generic reward descriptor, the block-first full-inventory granter, a
durable claim store, and (the score-driven layer) a pack-authorable loot table. Common ships the MODEL +
the granter only; the consumer's `Sink` interprets currency/command kinds, so common imports no currency
engine. World-thread for grants; the roll + parse are pure.

- **[`InstanceReward`](InstanceReward.java)** - `record(Kind kind, String id, int quantity, String displayKey,
  String iconItemId)`, `Kind` = `ITEM`/`CURRENCY`/`COMMAND`. Pack-authored as a compact spec
  (`item <id> <qty> [displayKey]` / `currency <id> <amt> [displayKey]`) via `parse`/`parseAll` (the codec has
  no list-of-objects form, so reward lists are `String[]`); Java-authored via `item`/`currency`/`command`.
  `iconItemId` is an OPTIONAL results-chip icon (null for a plain item/currency spec). The single reward
  currency every other class here speaks.
- **[`RewardSpecRegistry`](RewardSpecRegistry.java)** - the EXTENSION POINT that lets a consumer add its own
  kind TOKEN (e.g. `xp`) to the grammar without common learning the domain: `register(token, Kind, idTransform,
  iconResolver)`. Both parsers consult it for an unknown token, mapping it to an existing `Kind` + a pure id
  rewrite (a command template for `COMMAND`, which may hold `{player}`/`{amount}` placeholders) + an optional
  icon. Register at consumer `setup()` (before `LoadedAssetsEvent`); an unregistered token parses to `null`
  (the entry drops), so a spec authored for an absent mod never becomes a phantom reward. The granter
  substitutes `{amount}` from the quantity; the consumer's `Sink` substitutes `{player}`. The MMO registers
  `xp` -> `/mmoawardxp` so a loot table can author skill-XP rewards with no XP concept in common.
- **[`WinGate`](WinGate.java)** - `ANY`/`WIN`/`LOSS` per-entry outcome gate on a `LootEntry` (default `WIN`):
  the "pay a consolation/participation reward on a loss without also handing out the win spoils" seam.
- **[`InstanceRewardGranter`](InstanceRewardGranter.java)** - `grantAll(rewards, player, ref, store, sink)`
  -> `GrantOutcome`. BLOCK-FIRST full-inventory guard: an `ITEM` is granted only if it all fits
  (`InventoryUtil.canFit`), else held in `GrantOutcome.pending()` (never partially delivered). Non-throwing,
  isolate-each. Currency/command run through the consumer `Sink`.
- **[`GrantOutcome`](GrantOutcome.java)** - `record(granted, blocked, failed, pending)`; `anyGranted`/`anyBlocked`.
- **[`RewardOnExit`](RewardOnExit.java)** - `NONE`/`ON_WIN`/`ALWAYS` + `grantsOn(win)`: the per-instance
  policy the consumer reads at its resolve choke-point.
- **[`PendingRewardStore`](PendingRewardStore.java)** - durable per-player reward queue (file-backed JSON):
  `queue`/`drain`/`has`. Holds owed spoils across disconnect/restart and re-holds anything that still does
  not fit at claim time.
- **Score-tiered loot tables** (the "better loot for a better score" layer):
  - **[`LootEntry`](LootEntry.java)** - one weighted, score-gated, quantity-ranged, win-gated pool entry.
    Compact spec is a superset of `InstanceReward`'s:
    `[w<weight>] [s<minScore>] [win|loss|any] <kind> <id> <qty|min-max> [displayKey]` (a registered token is
    accepted in `<kind>`). `parse`/`parseAll`; `resolve(Random)` rolls the quantity; `safeWeight()` clamps
    `>= 0`. The `win`/`loss`/`any` token never collides with `w<weight>` (the weight flag requires digits).
  - **[`LootTable`](LootTable.java)** - `record(guaranteed, pool, rolls, scorePerBonusRoll, maxRolls,
    sourceId, tableId)` + `roll(int score, boolean win, Random)`: each guaranteed entry whose `WinGate`
    admits the outcome, plus `clamp(rolls + score/scorePerBonusRoll, 0, maxRolls)` weighted picks among pool
    entries eligible at the score AND gate. `guaranteed` is a `List<LootEntry>` too (so a guaranteed reward
    can be win/loss-gated). Deterministic for a given seed.
  - **[`LootTableAsset`](LootTableAsset.java)** + **[`LootTableConfig`](LootTableConfig.java)** - Pattern-A
    codec (`Server/<Mod>/LootTables/`, registered by `asset/FrameworkAssetRegistrar`) + its
    `defaults < pack < owner` fold. Lists are `String[]` (`Guaranteed`/`Pool`); knobs `Rolls`/
    `ScorePerBonusRoll`/`MaxRolls`; the optional `TableId` groups ADDITIVE contributions; the optional
    `NativeDropList` names a native Hytale `ItemDropList` asset this table delegates item selection to (see
    below). **`LootTableConfig.resolveUnion(tableId)`** is the additive resolver: it folds EVERY loaded table
    whose `TableId` matches into one (entries concatenated, contributors ordered by source id for a stable
    roll, scalars - including `NativeDropList` - from the base whose own id == `tableId`), so a second pack
    adds entries to a table WITHOUT overriding the file that owns it. `TableId` defaults to the asset's own
    id, so a lone table folds to itself and `resolveUnion` is a safe drop-in for `resolve`.
  - **[`NativeLootService`](NativeLootService.java)** - the XP-AGNOSTIC engine-touching half of the
    primitive: `rollNative(dropListId)` wraps `ItemModule.getRandomItemDrops` (empty + warn-once on a
    disabled module or an unclaimed id, mirroring the sibling `mmo-mob-scaling`
    `MobScalingLootDropSystem`'s `WARNED_IDS`; never throws) and `spawnInWorld(store, commandBuffer,
    position, rotation, items)` wraps `ItemComponent.generateItemDrops` + `CommandBuffer.addEntities` (a
    no-op on an empty list). These two are the reusable primitives a consumer's OWN system calls to roll +
    ground-spawn a native table (luck-loot, mob-scaling bonus loot); common ships them so the native-roll +
    in-world-spawn idiom is written once. `rollTable(table, score, win, rng)` is the drop-in replacement for
    a consumer's `table.roll(score, win, rng)` call: it rolls the table EXACTLY as before for its own
    command/currency/gated entries, then, when `table.nativeDropList()` is set, rolls that native list too
    and appends one `InstanceReward.item(...)` per resolved `ItemStack` on top. A `null`/blank
    `nativeDropList` is a byte-for-byte pass-through (no native delegation, the pre-native behavior).
    `LootTable.roll` itself stays pure and engine-free; only `NativeLootService` touches `ItemModule`.

**Consumer flow (Kweebec is the exemplar, `experience/KweebecExperience`):** at round resolve, with the
per-player score AND win/loss outcome in hand, `LootTableConfig.resolveUnion(preset.rewardTableId())
.roll(score, win, seed)` ONCE, `PendingRewardStore.queue` the concrete rolled list (durable, no grant),
stash the same list for the chip preview, then `grantAll` on the player's Claim back in the overworld. A
preset that should pay a participation reward on a loss sets `RewardOnExit: ALWAYS` and gates its win-only
entries `win` (the default); loss/any entries then pay on a loss.

**Tests** (`src/test/.../instance/reward/`): `LootEntryTest` (grammar + range resolve + gate tokens +
registered-token rewrite), `LootTableTest` (determinism, score gating, bonus-roll scaling, cap, win/loss
gating), `LootTableUnionTest` (the additive union, incl. the `NativeDropList` base-scalar rule),
`InstanceRewardParseTest` (spec + registry parse), `NativeLootServiceTest` (native-delegation merge,
no-native-drop-list pass-through, unknown-id / disabled-module never-throws). `LootTableAsset.CODEC` is in
`asset/AssetCodecInitTest` (PascalCase static-init guard). A bare unit-test JVM never boots a real
`ItemModule` (its static `get()` is only assigned by the live plugin bootstrap) or registers the `Item`/
`ItemDropList` asset stores, so `NativeLootServiceTest` stubs `NativeLootService`'s package-private
engine-roll seam for the native-item cases, and builds any needed `ItemStack` via `ItemStack.CODEC.decode`
(NOT the public constructors, which call `getItem()` and NPE with no registered `Item` asset store) rather
than touching the live engine.
