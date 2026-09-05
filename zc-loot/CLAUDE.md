# CLAUDE.md - zc-loot

The loot / reward layer: the reward + `Lootable` model, the weighted roll math, the stamp-onto-item
math, the native drop-list spawner, and the deferred-payout + pending-grant store. This is the
shared grant substrate every progression and instance engine rests on, so it depends on `zc-core`
and nothing else - it must stay reachable from anywhere in the graph.

**For the full module narrative** (the reverse-edge trap, the reward-kind seam, the consumer flow
exemplar), read [`instance/reward/CLAUDE.md`](src/main/java/com/ziggfreed/common/instance/reward/CLAUDE.md)
first - it doubles as this module's deepest router and this file does not repeat it in full.

## Build

Part of the fourteen-module `ziggfreed-common` build (`gradle/zc-module.gradle` convention, Java 25,
compiles as `:zc-loot`). See the root [`CLAUDE.md`](../CLAUDE.md) for the aggregate build.

## Dependencies

- **Depends on**: `zc-core` only.
- **Depended on by**: `zc-instance` (the reward granter + `InstanceReward`), `zc-objectives` (the
  same, plus the loot core for its native-event producers), `zc-progression` (the shared reward
  VOCABULARY in `loot/reward/` - what a reward is, who pays it out), `zc-presentation` (one seam,
  `RewardToastLines`, turning a reward-chip reading into toast body rows), `zc-commerce` (the
  same vocabulary a priced offer carries and `ShopEngine` pays out through `RewardGrants`), and
  `zc-encounter` (a defeated boss's payout: it rolls a `LootRef` through the loot core, turns the
  grants into `RewardSpec`s and pays each credited participant through `RewardGrants`, share-scaled).
- **Reverse-edge trap**: this module may NEVER import `zc-progression`, `zc-objectives`,
  `zc-instance`, `zc-encounter`, `zc-commerce`, `zc-dialogue`, `zc-presentation`, `zc-world`, or
  `zc-effects` - sitting below six consumers means any edge back up is an immediate cycle. A grant
  that needs a native effect, a page, a world
  identity, or a conversation is a SEAM this module declares (a registered `RewardSpec` kind) and
  the wiring root or the consumer fills, never an import. `zc-effects` in particular must never
  appear here nor the reverse - see that module's own reverse-edge note.

## Packages

- [`loot/`](src/main/java/com/ziggfreed/common/loot/CLAUDE.md) - the ONE loot core: `Roll`
  (`{Trigger, Conditions, Chance, Ladder, Grants, Cue}`), `RollEvaluator` (the pure decision),
  `LootEngine` (the seam-driven half that acts), `LootableAsset`/`LootableConfig`/`LootRef` (the
  authoring surface, `Server/ZiggfreedCommon/Lootables/`, its config folding `ContributesTo`
  enrichers so every reader sees the enriched table), `LootFactors` (an instance run's score/
  outcome as ordinary factor readings).
  - [`loot/reward/`](src/main/java/com/ziggfreed/common/loot/reward/CLAUDE.md) - the ONE reward
    vocabulary every payout site shares: `RewardSpec`, `RewardHandler`, `RewardKindRegistry`,
    `RewardGrants`. `LootRewardKinds` ships the four kinds the framework itself pays out (`Item`,
    `Lootable`, `Stamped_Item`, `Command`), and `DroplistRewardKind` a fifth that rolls a native
    `ItemDropList` onto the ground; the wiring root registers `Droplist` beside `Effect`, the
    latter because this module may never see the effect module.
  - [`loot/stamp/`](src/main/java/com/ziggfreed/common/loot/stamp/CLAUDE.md) - rolling stats onto
    an item: `StatRollEntry`/`RollPoolAsset`/`StampSpec` authored, `StampCapEngine` the pure
    decision (lowest budget binds, a fully-capped attempt denied so nothing is charged), the
    pluggable `Stamper` contract with `StackStatsStamper` the wiring root installs by default.
- [`instance/reward/`](src/main/java/com/ziggfreed/common/instance/reward/CLAUDE.md) - the
  mod-agnostic reward MODEL + the deferred-payout layer: `InstanceReward`, `InstanceRewardGranter`
  (block-first full-inventory guard), `PendingRewardStore` (durable per-player queue),
  `DeferredRewards` (the one translation from what a `LootEngine.select` decision decided into
  rewards a player can be shown now and handed later), `LootEntry`/`WinGate` (the terse compact
  spec grammar, offered to a codec field that can only hold a `String[]`; no consumer speaks it
  today, so its own tests are the whole of what pins it), and `NativeLootService` (the XP-agnostic
  engine-touching half: roll a native `ItemDropList`, spawn it on the ground - tick-safe, with the
  one-accessor form re-queuing a mid-tick store add onto the owning world's thread, `spawnAtFeet` as
  the drop-at-feet primitive the default `FeetDropOverflow` sink drops through, and every spawn form
  answering landed-or-queued vs lost).

## Shipped resources

Three reward-kind presentation files, `Server/ZiggfreedCommon/RewardKinds/{Droplist,Effect,Lootable}.json`
- how each roll-at-grant-time kind READS on a chip before it pays out, nothing about what it pays
- plus the nine-locale `Server/Languages/<bcp47>/ziggfreedcommon.loot.lang` family holding the three
labels they name. `LootableAsset` is a registered content TYPE (`Server/ZiggfreedCommon/Lootables/`)
that ships no default table - the framework asset-store paradigm is defaults-optional. The stamp
side ships a second nine-locale family, `Server/Languages/<bcp47>/ziggfreedcommon.stamp.lang` (the
"Enhancements" tooltip heading and the one built-in stat label, durability), and two more registered
TYPES of that same defaults-optional shape: `RollPoolAsset` (`Server/ZiggfreedCommon/RollPools/`) and
`StatDisplayAsset` (`Server/ZiggfreedCommon/StatDisplays/`, `{Key, Color}` keyed by stat id). Both are
detailed in the `loot/stamp/` router.

## Conventions

World-thread for grants and native rolls; the decision passes (`RollEvaluator`, `StampCapEngine`,
`DeferredRewards.from`) are pure and unit-testable without a booted engine. A consumer contributes a
reward-spec KIND token (e.g. `xp`) by registering a `RewardAuthoring` adapter on `RewardKinds.shared()`
rather than this module ever holding a table of tokens itself; an unregistered token drops the entry
rather than granting a phantom reward. **What loot IS lives in `loot/` only** - a score-tiered table
is an ordinary `Lootable` there (conditional `Rolls` for everyone, a `Pool` for the varying part);
anything in `instance/reward/` that reads like a second loot model is a mistake, per that package's
own router.

## Tests

30 files: the loot core (`LootEngineTest`, `RollEvaluatorTest`, `LootableAssetCodecTest`,
`LootableContributionTest`, `LootableValidatorTest`, `LootPoolTest`, `LootFactorGateTest`,
`LootEntryTest`), the reward vocabulary (`RewardKindRegistryTest`, `RewardKindFoldTest`,
`RewardKindAssetCodecTest`, `RewardKindAssetFoldTest`, `RewardKindValidatorTest`,
`RewardGrantsTest`, `CommandRewardKindTest`, `DroplistRewardKindTest`, `FrameworkKindFailLoudTest`,
`RewardChipsTest` - how one reward READS before it is granted, from strings alone,
`CommandRewardFitTest` - which command rewards need inventory room at all, `RewardJsonTest` - how a
hand-authored reward reads as a kind plus flat parameters, how a consumer's dialect renames both,
and why a reward missing what its kind requires is refused at LOAD rather than at payout,
`LootableRewardSinksTest` - that a rolled table's own `Rewards` genuinely pay through the registry
the kind was registered into, and that its earned cues reach whatever presenter the server
registered, and `ShippedRewardKindFilesTest` - the three presentation-only kind files this module
ships (`Lootable`, `Droplist`, `Effect`) decoding command-less and reading as the generic localized
line under a stand-in icon), the stamp math (`StampCapEngineTest`, `StamperDescribeTest`,
`StampTooltipGateTest` - whether an item's own description key is safe to nest above the enhancement
lines, refusing a key the catalogue does not carry and one carrying markup this surface cannot
parse), and the
deferred-payout layer (`DeferredRewardsTest`, `InstanceRewardParseTest`, `InstanceRewardMergeTest`,
`NativeLootServiceTest`, `PendingRewardStoreTest` - the durable file's version marker exercised
through queue/drain/has across a re-read: a file written before the marker existed reads as version
1, every write carries the version, and a file declaring a newer one is left unread).
`NativeLootServiceTest`'s unknown-id/disabled-module/throwing-engine
cases run against the real unbooted engine (a bare unit-test JVM cannot construct an `ItemStack` at
all here) - see that package's router for why.
