# instance/reward/ - the mod-agnostic reward model + score-tiered loot tables

Router for `com.ziggfreed.common.instance.reward`. The reusable end-game reward layer a consumer
minigame/dungeon grants from: a generic reward descriptor, the block-first full-inventory granter, a
durable claim store, and (the score-driven layer) a pack-authorable loot table. Common ships the MODEL +
the granter only; the consumer's `Sink` interprets currency/command kinds, so common imports no currency
engine. World-thread for grants; the roll + parse are pure.

- **[`InstanceReward`](InstanceReward.java)** - `record(Kind kind, String id, int quantity, String displayKey)`,
  `Kind` = `ITEM`/`CURRENCY`/`COMMAND`. Pack-authored as a compact spec (`item <id> <qty> [displayKey]` /
  `currency <id> <amt> [displayKey]`) via `parse`/`parseAll` (the codec has no list-of-objects form, so
  reward lists are `String[]`); Java-authored via `item`/`currency`/`command`. The single reward currency
  every other class here speaks.
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
  - **[`LootEntry`](LootEntry.java)** - one weighted, score-gated, quantity-ranged pool entry. Compact spec
    is a superset of `InstanceReward`'s: `[w<weight>] [s<minScore>] <kind> <id> <qty|min-max> [displayKey]`.
    `parse`/`parseAll`; `resolve(Random)` rolls the quantity; `safeWeight()` clamps `>= 0`.
  - **[`LootTable`](LootTable.java)** - `record(guaranteed, pool, rolls, scorePerBonusRoll, maxRolls)` +
    `roll(int score, Random)`: the guaranteed list plus `clamp(rolls + score/scorePerBonusRoll, 0, maxRolls)`
    weighted picks among pool entries eligible at the score (`minScore <= score`), each resolved to a concrete
    quantity. Deterministic for a given seed (mirrors `instance/encounter/SpawnRoster`'s weighted pick).
  - **[`LootTableAsset`](LootTableAsset.java)** + **[`LootTableConfig`](LootTableConfig.java)** - Pattern-A
    codec (`Server/<Mod>/LootTables/`, registered by `asset/FrameworkAssetRegistrar`) + its
    `defaults < pack < owner` fold. Lists are `String[]` (`Guaranteed`/`Pool`); knobs `Rolls`/
    `ScorePerBonusRoll`/`MaxRolls`. A consumer's preset references a table by id
    (`InstancePresetAsset.RewardTableId`) and resolves it here at its reward choke-point.

**Consumer flow (Kweebec is the exemplar, `experience/KweebecExperience`):** at round resolve, with the
per-player score in hand, `LootTableConfig.resolve(preset.rewardTableId()).roll(score, seed)` ONCE,
`PendingRewardStore.queue` the concrete rolled list (durable, no grant), stash the same list for the chip
preview, then `grantAll` on the player's Claim back in the overworld. The roll is the ONLY randomness; the
queued list is the source of truth so the preview matches the payout.

**Tests** (`src/test/.../instance/reward/`): `LootEntryTest` (grammar + range resolve), `LootTableTest`
(determinism, score gating, bonus-roll scaling, cap, guaranteed). `LootTableAsset.CODEC` is in
`asset/AssetCodecInitTest` (PascalCase static-init guard).
