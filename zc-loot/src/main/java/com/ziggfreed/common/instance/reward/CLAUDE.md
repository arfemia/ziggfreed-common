# instance/reward/ - the mod-agnostic reward model + score-tiered loot tables (module `zc-loot`)

Router for `com.ziggfreed.common.instance.reward`, and the module-level router for **zc-loot**. Its
sibling packages, both of which you should read before adding anything of their shape anywhere:
[`loot/`](../../loot/CLAUDE.md) (the ONE loot core - `Roll`, the pure evaluator, the seam-driven
engine, the `Lootable` and `RollPool` assets, the stamp math) and
[`loot/reward/`](../../loot/reward/CLAUDE.md) (the reward VOCABULARY every payout site shares - what
a reward IS, who pays it out, how it is written, and the isolated payout pass). The reusable end-game reward layer a consumer minigame/dungeon grants from: a generic reward
descriptor, the block-first full-inventory granter, a durable claim store, and (the score-driven
layer) a pack-authorable loot table. Common ships the MODEL + the granter only; the consumer's `Sink`
interprets currency/command kinds, so common imports no currency engine. World-thread for grants; the
roll + parse are pure.

## The module

`zc-loot` depends on **zc-core and nothing else**, and that is the whole point of it existing: loot
and rewards are the grant substrate the progression engines and the instance-experience layer both
rest on, so it has to be reachable from anywhere in the graph. `zc-progression` and `zc-instance`
both depend on it - progression for the shared reward vocabulary in `loot/reward/`, instance for this
package.

**REVERSE-EDGE TRAP - this module may never import progression, instance, dialogue, presentation, or
world.** Sitting below two consumers means any edge back up is an immediate cycle. A grant that needs
a native effect, a page, a world identity or a conversation is a SEAM this module declares and the
wiring root or the consumer fills - concretely, a registered reward KIND whose handler lives wherever
the capability does. `zc-effects` in particular must never appear here (nor the reverse): an
effect-granting reward dispatches through the reward-kind registry, which is exactly what keeps those
two apart.

Package naming to keep as this module grows: the tri-layer shape `<domain>/` + `<domain>/asset/` +
`<domain>/event/` (engine / authoring / outbound events), the same shape the progression module uses.

- **[`InstanceReward`](InstanceReward.java)** - `record(Kind kind, String id, int quantity, String displayKey,
  String iconItemId)`, `Kind` = `ITEM`/`CURRENCY`/`COMMAND`. Pack-authored as a compact spec
  (`item <id> <qty> [displayKey]` / `currency <id> <amt> [displayKey]`) via `parse`/`parseAll` (the codec has
  no list-of-objects form, so reward lists are `String[]`); Java-authored via `item`/`currency`/`command`.
  `iconItemId` is an OPTIONAL results-chip icon (null for a plain item/currency spec). The single reward
  currency every other class here speaks.
- **Consumer kind TOKENS** (e.g. `xp`) come from the ONE shared reward vocabulary, not a table of this
  package's own: register a `RewardAuthoring` adapter on `RewardKinds.shared()` and both compact parsers
  here consult it for an unknown token. The adapter expands the token's argument into a `RewardSpec`
  whose kind names one of `Kind` and whose `id` parameter is the reward id (a command template for
  `COMMAND`, which may hold `{player}`/`{amount}` placeholders); an optional `icon` parameter becomes the
  results chip's art. Register at consumer `setup()` (before `LoadedAssetsEvent`); an unregistered token
  parses to `null` (the entry drops), so a spec authored for an absent mod never becomes a phantom
  reward. The granter substitutes `{amount}` from the quantity; the consumer's `Sink` substitutes
  `{player}`. The MMO registers `xp` -> `/mmoawardxp` so a loot table can author skill-XP rewards with no
  XP concept in common.
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
    can be win/loss-gated). Deterministic for a given seed. **Eligibility rides the shared vocabulary**:
    an entry's score requirement and win/loss gate are `FactorCondition`s over `loot/LootFactors`
    (`ziggfreedcommon:instance_score` / `:instance_win`), walked by the same `loot/FactorGate` every other
    piece of gated content uses, and the pick runs through the one `util/WeightedPick` primitive. So
    "unlocks at 4000 points" is an ordinary reading rather than a rule only this class knows.
    `rollCount(score)` exposes the bonus-roll arithmetic on its own.
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
`InstanceRewardParseTest` (spec + authoring-adapter parse), `LootTableRebaseTest` (eligibility as
conditions, the bonus-roll count, and a PINNED fixed-seed run - that pin is the payout a player
receives, so a change there is a balance change, not a refactor), `NativeLootServiceTest` (native-delegation merge,
no-native-drop-list pass-through, unknown-id / disabled-module never-throws). `LootTableAsset.CODEC` is in
`asset/AssetCodecInitTest` (PascalCase static-init guard). A bare unit-test JVM never boots a real
`ItemModule` (its static `get()` is only assigned by the live plugin bootstrap) or registers the `Item`/
`ItemDropList` asset stores, so `NativeLootServiceTest` stubs `NativeLootService`'s package-private
engine-roll seam for the native-item cases, and builds any needed `ItemStack` via `ItemStack.CODEC.decode`
(NOT the public constructors, which call `getItem()` and NPE with no registered `Item` asset store) rather
than touching the live engine.
