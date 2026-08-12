# loot/reward/ - the reward VOCABULARY every payout site shares (module `zc-loot`)

Router for `com.ziggfreed.common.loot.reward`. A handful of small classes that answer one question for
the whole library: **what is a reward, who pays it out, how is it written, and what happens when a
payout fails.** A quest hand-in, an end-of-round spoils drop and an achievement unlock all grant
through these, so a consumer registers each reward kind ONCE and content authors it anywhere.

That is why it lives here rather than beside any one engine. `zc-loot` sits at the bottom of the
graph (zc-core only), so every engine above it can reach the vocabulary without any of them reaching
each other. Putting it in a lifecycle module would have made the next engine either depend on that
module or grow a second, subtly different idea of what a reward is.

- **[`RewardSpec`](RewardSpec.java)** - a KIND plus a bag of string parameters, immutable, keys
  matched case-insensitively. `of(kind)` / `of(kind, map)` / `of(kind, key, value)`; typed reads
  `param`/`paramOr`/`longParam`/`doubleParam`/`flagParam` fall back rather than throwing; `with` makes
  an adjusted copy. `longParam` accepts a decimal that names a whole number (`"5.0"` reads as 5)
  because authoring formats are hand-written JSON and every layer has to agree what the field says -
  a preview reading it loosely while the granter reads it strictly is how a player is shown five of
  something and handed one. **Deliberately not a fixed field set**: a reward's real schema belongs to whoever
  defines the kind, and strings are the one representation every authoring format and every handler
  already agrees on.
- **[`RewardHandler`](RewardHandler.java)** - `grant(spec, subject)` (may throw; the caller isolates)
  plus the optional `retryCommand(spec, subject, sourceId)`. **Write a `retryCommand` whenever the
  reward is replayable** - it is the difference between a failed payout being queued for next connect
  and being genuinely lost. There is also a `grant(spec, subject, sourceId)` overload, which is the
  one `RewardGrants` actually calls and which defaults to dropping the label: override it when the
  handler's own output names its source (a log line, a command placeholder), because without it a
  handler can only name the label it was REGISTERED under and every quest in the game reports the
  same word. Live and replayed payouts see the same `sourceId`.
- **[`RewardKindRegistry`](RewardKindRegistry.java)** - the open kind table over the shared
  `registry.RegistryLedger` (case-insensitive ids, idempotent per id, last-write-wins, per-kind owner
  + failure history via `info()`). **Nothing is pre-seeded**, on purpose: there is no such thing as a
  generic reward, so an empty registry grants nothing and reports each unhandled kind once. It also
  carries the AUTHORING facet - `registerAuthoring`/`authoring`/`expand`/`authoringTokens` - so a
  kind's runtime half and its writing half hang off ONE id. They used to be two tables, and the cost
  was what a split registry always costs: a kind could be authorable and unpayable, or payable and
  unwritable, with nothing in either table able to notice.
- **[`RewardAuthoring`](RewardAuthoring.java)** - `expand(arg) -> RewardSpec`, for the terse formats
  where a reward is one word plus an argument (`"xp MINING 500"`) and something has to know what the
  middle word means. OPTIONAL: a kind only ever written as a structured `{Kind, Params}` object needs
  none. A token may expand into a completely different kind than its own name.
- **[`RewardKinds`](RewardKinds.java)** - the process-wide `shared()` vocabulary, for the parse paths
  that cannot be HANDED a registry (an asset decoding a list of compact strings). Most engines should
  keep taking their registry as a parameter; reach for this only from a static parse site.
- **[`LootRewardKinds`](LootRewardKinds.java)** - the three kinds the framework itself pays out:
  `item` (`Item`/`Count`), `lootable` (`Lootable`/`Trigger` - rolls a named table), and
  `stamped_item` (`Item`/`Count` plus either `Pool` to roll fresh or `Stats` written out as
  `"Damage:5,Speed:2"`). Deliberately the only three: everything else a payout could mean belongs to
  the mod that owns the concept. Two static seams - `overflow(...)` for an item that will not fit and
  `factors(...)` for the vocabulary a rolled table's gates read. Plus `canAdd(spec, subject)`, the
  ASK-FIRST half of the item path: would this reward's item fit right now, granting nothing. A spec
  that needs no room answers true, INCLUDING the two it cannot know about (a `lootable` rolls its
  contents at grant time; another mod's kind is that mod's business), so a false answer always names
  a specific item that specifically will not fit. It probes through
  [`inventory/InventoryGrant.canAdd`](../../inventory/CLAUDE.md) - the same machinery a grant lands
  through, and the one fit check every consumer mod shares. **`canAdd` answers about ONE reward.
  Checking a LIST with it in a loop is a bug**: each call asks about the same last free slot, so
  every reward answers yes and the last one still lands on the floor. Collect `stackFor(spec)` over
  the list (null = needs no room) and ask `InventoryGrant.canAddAll` once - `RewardFit` in the MMO
  is the worked example.
- **[`DroplistRewardKind`](DroplistRewardKind.java)** - the fourth framework kind, `droplist`
  (`Droplist`/`Rolls`/`Position`): rolls a NATIVE Hytale `ItemDropList` asset and spills the stacks on
  the GROUND through the same call the engine drops mob loot with, delegating both halves to
  [`instance/reward/NativeLootService`](../../instance/reward/CLAUDE.md). It sits in its own class
  rather than joining `LootRewardKinds` because it is not an inventory grant: a full bag is not a
  failure case, it needs no overflow sink, and its `canAdd` answer is unconditionally yes. `Position`
  is spawned at verbatim; omitted, the stacks land one block above the receiving player. A caller
  that knows a better spot - a corpse, a broken block - writes one in with
  `spec.with("Position", x + "," + y + "," + z)` at the moment it knows it. `Rolls` repeats the whole
  roll, matching the engine's own `/droplist <id> [count]` preview; it is not a luck or weight knob,
  and per-pool counts belong on the list's own `RollsMin`/`RollsMax`. No `retryCommand`: what a
  droplist produces is decided by a roll at payout time, so a replay would hand over a different
  reward than the one that failed.
- **[`RewardGrants`](RewardGrants.java)** - `grantAll(rewards, subject, sourceId, kinds, retryQueue,
  warn)` -> `GrantOutcome(granted, queued, failed)`. Per-reward isolation: one handler throwing never
  costs the player the rest, a replayable failure becomes a queued retry, and only a reward that can
  be neither granted nor queued counts as lost. Never throws.

## Rules to keep

- **Nothing here may learn a domain.** No currency, no progression, no item system - a payout that
  needs one of those is a registered KIND whose handler lives where the capability does. The
  `REVERSE-EDGE TRAP` on the module (see [`../../instance/reward/CLAUDE.md`](../../instance/reward/CLAUDE.md))
  is what keeps that honest.
- **`sourceId` labels a payout, it does not classify it.** Convention is `"<what paid out>:<its id>"`;
  it reaches logs and any retry command, and nothing branches on it.
- **A caller reads `GrantOutcome`.** Never assume a payout pass succeeded - the three counts are the
  only place the difference between paid, queued and lost is recorded.
- **A failing grant is better than a pretended one.** `LootRewardKinds` THROWS when an item went
  nowhere, because throwing is what turns a lost reward into a queued retry. A handler that swallows
  a failure has silently thrown the reward away.
- **A reward that cannot name what it pays out THROWS too** - no `Item`, a `Count` of none, a missing
  `Lootable`/`Droplist`/`Effect`, a table id no pack answers to. Returning quietly reports the reward
  as PAID, so a payout site that charged a price or spent a completion first has no reason to refund
  it. The throw names the parameter at fault, and the same rule reaches `retryCommand`: a spec with
  nothing to hand over is not replayable either (rounding a count of none up to one would pay out
  something nobody authored). `FrameworkKindFailLoudTest` covers every kind.
- **One registry, both facets.** A new reward capability registers a handler; a new terse spelling
  registers an authoring adapter; neither justifies a second table.
- Tests are mechanics and invariants: `RewardGrantsTest` (isolation, retry queueing, unhandled kinds,
  the typed parameter reads), `FrameworkKindFailLoudTest` (an unpayable spec is reported failed, never
  granted), `RewardKindRegistryTest` (empty start, registration, lookup),
  `RewardKindFoldTest` (the two facets keyed together, and both compact grammars reading one table),
  and `DroplistRewardKindTest` (the whole parameter fold - which id, how many rolls, where they land -
  since every one of those is a way a payout could quietly go somewhere nobody meant it to; the roll
  and the spawn themselves are engine calls the in-game pass covers).
