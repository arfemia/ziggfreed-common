# instance/reward/ - the mod-agnostic reward model + the deferred-payout layer (module `zc-loot`)

Router for `com.ziggfreed.common.instance.reward`, and the module-level router for **zc-loot**. Its
sibling packages, both of which you should read before adding anything of their shape anywhere:
[`loot/`](../../loot/CLAUDE.md) (the ONE loot core - `Roll`, the pure evaluator, the seam-driven
engine, the `Lootable` and `RollPool` assets, the stamp math) and
[`loot/reward/`](../../loot/reward/CLAUDE.md) (the reward VOCABULARY every payout site shares - what
a reward IS, who pays it out, how it is written, and the isolated payout pass). The reusable end-game
reward layer a consumer minigame/dungeon grants from: a generic reward descriptor, the block-first
full-inventory granter, a durable claim store, and the translation that turns a decided loot pass into
rewards a player can be SHOWN before they are handed over. Common ships the MODEL + the granter only;
the consumer's `Sink` interprets currency/command kinds, so common imports no currency engine.
World-thread for grants; the decision + parse are pure.

**What loot IS lives one package over, in [`loot/`](../../loot/CLAUDE.md).** A score-tiered table is an
ordinary `Lootable` there - conditional `Rolls` for what everybody gets, a `Pool` for the part that
varies - and this package's job begins once that table has DECIDED. Anything that reads like a second
loot model appearing here is a mistake; add the knob to the one core instead.

## The module

`zc-loot` depends on **zc-core and nothing else**, and that is the whole point of it existing: loot
and rewards are the grant substrate the progression engines and the instance-experience layer both
rest on, so it has to be reachable from anywhere in the graph. `zc-progression`, `zc-objectives`,
`zc-instance`, `zc-presentation` and `zc-commerce` all depend on it - progression and objectives for
the shared reward vocabulary in `loot/reward/`, presentation for what a reward chip renders, commerce
for what a priced offer pays out, instance for this package.

**REVERSE-EDGE TRAP - this module may never import progression, instance, dialogue, presentation, or
world.** Sitting below five consumers means any edge back up is an immediate cycle. A grant that needs
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
  `{player}`, reading the player off the ref it is handed. The MMO registers `xp` -> `/mmoawardxp` so a loot table can author skill-XP rewards with no
  XP concept in common.
- **[`WinGate`](WinGate.java)** - `ANY`/`WIN`/`LOSS` per-entry outcome gate on a `LootEntry` (default `WIN`):
  the "pay a consolation/participation reward on a loss without also handing out the win spoils" seam.
- **[`InstanceRewardGranter`](InstanceRewardGranter.java)** - `grantAll(rewards, ref, store, sink)`
  -> `GrantOutcome`. BLOCK-FIRST full-inventory guard: an `ITEM` is granted only if it all fits
  (`InventoryUtil.canFit`), else held in `GrantOutcome.pending()` (never partially delivered). Non-throwing,
  isolate-each. Currency/command run through the consumer `Sink`, which is handed `(ref, store)` and reads the player off
  that ref rather than being passed one.
- **[`GrantOutcome`](GrantOutcome.java)** - `record(granted, blocked, failed, pending)`; `anyGranted`/`anyBlocked`.
- **[`RewardOnExit`](RewardOnExit.java)** - `NONE`/`ON_WIN`/`ALWAYS` + `grantsOn(win)`: the per-instance
  policy the consumer reads at its resolve choke-point.
- **[`PendingRewardStore`](PendingRewardStore.java)** - durable per-player reward queue (file-backed JSON):
  `queue`/`drain`/`has`. Holds owed spoils across disconnect/restart and re-holds anything that still does
  not fit at claim time. The file carries `"version": 1` (absent reads as 1, so a pre-marker file
  still loads; a newer version is warned about and left unread).
- **[`DeferredRewards`](DeferredRewards.java)** - the ONE translation from what a loot pass DECIDED
  (`LootEngine.select`, a list of `Selected(grants, cue)`) into `InstanceReward`s that can be shown
  now and handed over later. `from(grants, kinds, subject, sourceId, warn)` /
  `fromSelection(selected, ...)`. Items become item rewards so the inventory guard still applies;
  `DropLists` are rolled HERE, at decision time; `Commands` become command rewards; a registered-kind
  `Rewards` entry is asked for its REPLAYABLE console line and becomes a command reward. **A kind that
  offers no replay is DROPPED and reported** - its payout is decided at grant time, so promising it on
  a results screen and paying something else later is worse than not promising it. A chip's label and
  art are settled in THREE rungs, first one that exists winning: the reward's own OPTIONAL `NameKey`
  and `Icon` parameters (read off any entry by this layer, because a kind's own schema decides what it
  PAYS, not how somebody else's screen draws it), then - for a kind written as a file - that kind's
  `RewardKindAsset.Presentation` defaults resolved against this reward's parameters, then nothing,
  which leaves the chip to work a label out from the reward itself. The middle rung is what stops
  every reward of one kind repeating the same two lines; a Java-registered kind has no file and so no
  presentation to ask for. The chip quantity comes from `Amount`/`Count`/`Quantity`.
- **[`LootEntry`](LootEntry.java)** + **[`WinGate`](WinGate.java)** - the terse COMPACT surface for a
  weighted, score-gated, quantity-ranged, win-gated reward, one line each:
  `[w<weight>] [s<minScore>] [win|loss|any] <kind> <id> <qty|min-max> [displayKey]` (a registered token
  is accepted in `<kind>`). `parse`/`parseAll`; `resolve(Random)` rolls the quantity; `safeWeight()`
  clamps `>= 0`. The `win`/`loss`/`any` token never collides with `w<weight>` (the weight flag requires
  digits). Eligibility rides the shared vocabulary: `conditions()` / `gateConditions()` are
  `FactorCondition`s over `loot/LootFactors` (`ziggfreedcommon:instance_score` / `:instance_win`),
  walked by the same `loot/FactorGate` every other piece of gated content uses. For a codec field that
  is a plain `String[]` and a whole pool visible at a glance; the structured surface with more reach
  (conditions over any factor, every grant leaf, contributions from other packs) is a `Lootable`'s own
  `Pool` group. **Nothing speaks this grammar today** - every site that once did authors a `Lootable`
  `Pool` instead, and `LootEntryTest` is the only thing holding the parser to its contract. Reach for
  it only where a codec field genuinely cannot hold anything but a `String[]`; anywhere else the
  structured group is the surface to author.
- **[`NativeLootService`](NativeLootService.java)** - the XP-AGNOSTIC engine-touching half of the
  primitive: `rollNative(dropListId)` wraps `ItemModule.getRandomItemDrops` (empty + warn-once on a
  disabled module or an unclaimed id, mirroring the sibling `mmo-mob-scaling`
  `MobScalingLootDropSystem`'s `WARNED_IDS`; never throws) and the spawn family wraps
  `ItemComponent.generateItemDrops` + `addEntities` (a no-op answering true on an empty list). The
  `spawnInWorld(store, commandBuffer, ...)` PAIR form is the preferred route inside a tick (the
  buffer queues the add); the one-accessor `spawnInWorld(accessor, ...)` form carries the SAFETY NET
  for the payout paths that cannot thread a buffer: a `Store`-routed add the engine rejects mid-tick
  (the store's write-processing assert, which a reward granted off a moment producer's tick would
  otherwise die on) is re-queued onto the owning world's thread (via `cast/WorldEvictors.worldOf`)
  and lands right after the tick. `spawnAtFeet(ref, items)` is the drop-at-feet primitive on top
  (position + the engine's own mob-drop lift), the one an overflow sink drops through. Every spawn
  form ANSWERS: true = landed or queued on the world thread, false = nothing spawned and the warn
  names the exact stacks that were lost. These are the reusable primitives a consumer's OWN system
  calls to roll + ground-spawn a native table (luck-loot, mob-scaling bonus loot, a `Droplist` reward
  kind, the default `FeetDropOverflow` sink); common ships them so the native-roll + in-world-spawn
  idiom is written once, and nothing else in this module touches `ItemModule`.

**Consumer flow (Kweebec is the exemplar, `experience/KweebecExperience`):** at round resolve, with the
per-player score AND win/loss outcome in hand, resolve the preset's `RewardTableId` through
`LootableConfig.resolve` (contributions already folded in), `LootEngine.select` it ONCE against
`LootFactors.lookupFor(score, win)` with a seeded sample source, hand the answer to
`DeferredRewards.fromSelection`, `PendingRewardStore.queue` the concrete list (durable, no grant), stash
the same list for the chip preview, then `grantAll` on the player's Claim back in the overworld. The
subject handed to `DeferredRewards` is deliberately named `"{player}"`, so a deferred command line keeps
the placeholder for whoever is standing there at claim time. A preset that should pay a participation
reward on a loss sets `RewardOnExit: ALWAYS` and leaves its consolation entries ungated.

**Tests** (`src/test/.../instance/reward/`): `LootEntryTest` (grammar + range resolve + gate tokens +
registered-token rewrite), `LootFactorGateTest` (what the two instance readings mean, and that a gate
over them fails closed with no run to ask about), `InstanceRewardParseTest` (spec + authoring-adapter
parse), `DeferredRewardsTest` (every leaf's deferral, all three presentation rungs and their order, and above all that an
unreplayable kind is dropped and reported rather than promised), `NativeLootServiceTest` (unknown-id /
disabled-module / throwing-engine never-throws, plus the empty-spawn contract: every spawn form
answers landed for an empty list before touching any accessor). A bare unit-test JVM never boots a real `ItemModule`
(its static `get()` is only assigned by the live plugin bootstrap) or registers the `Item`/
`ItemDropList` asset stores, and cannot construct an `ItemStack` at all here (its codec chain forces a
validator class needing the Hytale log manager installed before anything touches `java.util.logging` -
already lost to the Gradle test worker's own bootstrap), so those cases run against the real unbooted
engine to prove the guards directly and leave what a live drop list produces to the in-game pass.
