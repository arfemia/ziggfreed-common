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
  + failure history via `info()`; `registerQuietly` is the same write minus the ledger's own
  overwrite line, for the one caller that reports the swap better itself - see the fold below).
  **Nothing is pre-seeded**, on purpose: there is no such thing as a
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
- **[`LootRewardKinds`](LootRewardKinds.java)** - the four kinds the framework itself pays out:
  `Item` (`Item`/`Count`), `Lootable` (`Lootable`/`Trigger` - rolls a named table through the FULL
  grants vocabulary, wired in `lootableSinks`: `Items` land in the inventory, `DropLists` roll their
  native tables into it, `Commands` run as the console with the `Command` kind's own placeholder
  vocabulary, and the table's `Rewards` pay through the REGISTRY the kind was registered into - so a
  table pays the same whether a station, a mob drop or a quest reward rolled it, and a nested
  `Lootable` composes up to four levels deep before the grant refuses it as a loop; the pass's
  EARNED cues are forwarded to [`loot/LootCues`](../CLAUDE.md), `LootableRewardSinksTest` pins both
  halves),
  `Stamped_Item` (`Item`/`Count` plus either `Pool` to roll fresh or `Stats` written out as
  `"Damage:5,Speed:2"`), and `Command` (`Command`/`RunAs`/`DelayTicks` - one authored line, with
  `{player}`, `{uuid}`, `{source}` and the reward's own parameters substituted through the same
  machinery a kind FILE's template uses). **Prefer `Item` over a `Command` running `/give`**: the
  item kind is fit-checked before a claim is allowed and queueable when it does not fit. **Kind ids are native-asset style, PascalCase with underscores, and the
  framework's own are UNPREFIXED** - a consumer's carry that mod's prefix (`Mmo_Xp`), and every
  lookup is case-insensitive, so an older lower-case spelling in existing content still resolves.
  Deliberately the only four: everything else a payout could mean - currency, a level, a title -
  belongs to the mod that owns the concept. `Command` earns its place because running a command line
  is not any one mod's idea; a kind FILE is the same idea with a declared schema, for when the shape
  repeats across many rewards. Two static seams - `overflow(...)` for an item that will not fit and
  `factors(...)` for the vocabulary a rolled table's gates read. Plus `canAdd(spec, subject)`, the
  ASK-FIRST half of the item path: would this reward's item fit right now, granting nothing. A spec
  that needs no room answers true, INCLUDING the two it cannot know about (a `Lootable` rolls its
  contents at grant time; another mod's kind is that mod's business), so a false answer always names
  a specific item that specifically will not fit. It probes through
  [`inventory/InventoryGrant.canAdd`](../../inventory/CLAUDE.md) - the same machinery a grant lands
  through, and the one fit check every consumer mod shares. **`canAdd` answers about ONE reward.
  Checking a LIST with it in a loop is a bug**: each call asks about the same last free slot, so
  every reward answers yes and the last one still lands on the floor. `canAddAll(rewards, subject
  [, sourceId])` is the batch, and it is what a payout site calls, straight - a consumer with a
  player in hand builds a `Subject` for them and asks this, rather than wrapping it in a probe of
  its own.
  - **A `Command` reward whose line is a `give` counts as an item**, so `stackFor(spec, subject,
    sourceId)` reads one back through [`command/CommandRunner.readGive`](../../command/CLAUDE.md)
    and it joins the same batch. The line is resolved exactly as the grant will resolve it, so the
    probe and the payout cannot disagree about the item or the count. A command that hands over
    nothing still answers null and needs no room, and a line that cannot be resolved at all is read
    as needing no room rather than as a refusal - reporting a full bag for an authoring mistake
    would hide the mistake, and the grant path already fails loudly on it. `stackFor(spec)` without
    a subject stays the item-only reading, for a caller that has nobody to resolve a line for.
- **[`DroplistRewardKind`](DroplistRewardKind.java)** - the fourth framework kind, `Droplist`
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
  be neither granted nor queued counts as lost. Never throws. The overload taking a `playerOnline`
  flag is the one a payout site should reach for: it asks the two questions that belong to the
  REWARD in front of the pass, `deliverable` then `stamped`, so neither is left to a consumer to
  remember.
  - **`deliverable(rewards, playerOnline, sourceId, warn)`** drops the rewards authored
    `P_QUEUE_IF_OFFLINE` false when nobody is there to receive them, one warning each. That flag is
    the author saying "this one is only worth anything in the moment" - a celebration effect, a
    command that positions the player - and the honest reading is to drop it rather than park it for
    a connect where it would fire out of context. Absent reads as false.
  - **`stamped(rewards, sourceId)`** writes `P_SOURCE` onto every reward that does not already name
    one, so a log line and a `{source}` placeholder name what actually paid out rather than the word
    the handler was registered under.
  - Both are asked ONCE, in front of every kind, because both are statements about the REWARD and
    not about who is paying it out: the alternative is each handler remembering to ask, which is how
    one of them stops asking and the flag quietly means nothing on that path. `P_SOURCE` and
    `P_QUEUE_IF_OFFLINE` are declared here for the same reason - they are the two parameters every
    kind reads, so there is one spelling of each.
- **[`RewardJson`](RewardJson.java)** - reads a reward written as `type` plus flat fields, for the
  authoring formats no codec decodes (an override config, a hand-written content file, a JSON body
  carried on something else). `type` names the KIND and every other field becomes a parameter of the
  same name, so content authors a kind this library never heard of with nothing here changing;
  nested objects and arrays are skipped, because a parameter is a single value whichever kind reads
  it. **Three things vary per consumer and none of them can live here**, so a reader is built once
  with `using(kinds, paramKeys, refusals, warn)` and reused: which historical kind spellings still
  have to parse, which historical field spellings do, and what that consumer's own kinds REFUSE to
  be authored without. A consumer with no compat history passes `identity()` twice and a null rule.
  **A refusal happens at LOAD and that is the point**: the reward is skipped with a warning naming
  the file, so whoever authored it finds out at boot and in a content audit rather than a player
  finishing something and receiving nothing. A kind the dialect has no opinion about is never
  refused - the mod defining it may simply not be installed, which is an audit's job to report.
- **[`RewardChips`](RewardChips.java) + [`RewardChip`](RewardChip.java)** - how one reward READS,
  before it is granted. A chip is an optional item icon plus one already-composed client-resolved
  line, assembled from the same three sources the deferred-payout layer reads and in the same order
  (the spec's own `NameKey`/`Icon`, then the kind FILE's `Presentation`, then the item form), so the
  same reward cannot read differently on a quest panel, a storefront offer and a results strip. An
  authored `NameKey` is emitted through zc-core's `ContentKeys`, never as written: the engine
  namespaces a key by the `.lang` FILENAME it was defined in while content is authored without that
  namespace, so a key handed over verbatim is one no client resolves and the player reads the key.
  **The kind-FILE rung yields only when the key its template resolves to is actually shipped**
  (`ContentKeys.known`): the file is a DEFAULT, and a default pointing at nothing must not outrank
  the item form or a contributed reading - so a per-skill key family covers the skills it names
  while a skill it never heard of still reads through the rescue rung instead of painting a raw
  key. A reward's OWN `NameKey` stays as written even when nothing ships it (the author's typo has
  to be findable). The label key's blanks are filled per the kind file's `Presentation.Args` (see
  below); unauthored, the one `{0}` is the reward's amount, typed numerically so a `{0, number}`
  blank groups digits per locale. `itemChip(itemId, amount)` exposes the item-form reading for a
  contributed source whose reward turns out to BE an item (a parsed `/give` line), so it reads
  exactly like a declared item grant.
  **Nothing branches on a kind id**, and a reward nothing can NAME is dropped rather than guessed at -
  painting a raw kind token at a player reads as a promise of something called that, and the fix is a
  two-line `Presentation` on the kind file. One rung sits between naming and dropping: a kind's OWNER
  may `RewardChips.contribute(...)` a reading process-wide (asked only where the generic reading found
  nothing, so an authored `NameKey`, a kind-file `Presentation` whose key is shipped, or an item form
  always wins - and a reward's own authored `Icon` re-points a contributed chip too, so "a reward's
  own words and picture win first" holds on every rung), which is how a Java-registered kind whose
  rewards all read one way names them with zero per-reward authoring - the worked examples are
  zc-commerce's `Currency` kind reading as its wallet's own name and icon (`CurrencyChipReading`,
  contributed by the wiring root; the label is the shared reward amount-and-name composition,
  "+50 Bounty Tokens" with the amount a typed number the client groups) and the MMO's
  `MmoChipReading` (its computed boost/ability-mod/command lines). The library's own
  roll-at-grant-time kinds need no rung at all: zc-loot SHIPS presentation-only kind files for
  `Lootable`/`Droplist`/`Effect` (`src/main/resources/Server/ZiggfreedCommon/RewardKinds/`, NameKey
  into its own `ziggfreedcommon.loot.lang` family, stand-in icons, no `Command` - the
  `PRESENTATION_ONLY` decoration shape), so every consumer's chip surfaces read them out of the box
  (`ShippedRewardKindFilesTest` pins all three). `Plan` is the whole decision over
  strings alone, so what a chip will say is testable with no server (the key/Args RENDERING needs the
  catalogue, pinned in tests via `LangCatalog.overrideForTests`). It lives here rather than on any one
  screen for the same reason the vocabulary does: every surface that previews a payout has to read one
  reward the same way. **`RewardChip` is the ONE chip record in the library** - a quest panel, a
  storefront offer, a board contract and an instance results strip all paint this one, so a reward
  cannot read one way on a spoils screen and another way everywhere else. A surface whose payout is
  not a `RewardSpec` composes its own label and still hands back this record (zc-instance's
  `RewardChipRenderer` does exactly that for an `InstanceReward`); it never mints a second shape.
  The toast half is zc-presentation's `ui/toast/RewardToastLines`, the one bridge from chips to
  toast body rows (row cap + overflow line), so a payout's toast and the panel that previewed it
  paint the same rows. **A display NUMBER in a chip line is a TYPED numeric param on a
  `{0, number}` key (`Msg.num`, a kind file's `Args`, a composition key), NEVER
  `Msg.raw(NumberFormatter...)`** - the client's own locale decides the digit grouping, and the
  root `NumberDisplayHygieneTest` fails the build on a violation (`// NUMBER-OK: <reason>` is the
  one escape hatch, reason mandatory).

## Minting a kind with no Java at all

A kind does not have to be registered by a mod. `Server/ZiggfreedCommon/RewardKinds/<Id>.json`
declares a parameter schema plus one command line, and the fold registers it exactly like a Java one -
so a server with an admin command it wants paid out as a reward needs no plugin to say so, and a
third party extends the vocabulary without shipping code. A COMMAND-LESS file whose id a
Java-registered kind already answers is DECORATION rather than a dud: the fold skips it quietly (the
Java handler keeps the payout), the validator notes it as `PRESENTATION_ONLY` INFO, and its
`Presentation` group is what every chip surface reads for that kind - the asset-driven way to say how
a Java kind's rewards look without taking the payout over. A command-less file whose id NOTHING
answers stays the loud `NO_COMMAND` error it always was. The store and the fold are wired together
in `FrameworkAssetRegistrar`, in ONE `LoadedAssetsEvent` listener: the fold has to run after the
layers resolve AND after every mod's `setup()`, and a second listener for the same event would leave
that order to registration order.

- **[`RewardKindAsset`](RewardKindAsset.java)** - the Pattern A type: `Params` (a per-parameter
  `{Required, Default}` group, merged per PARAMETER under `Parent`) plus `Command` (one console line,
  substituting `{player}`, `{uuid}` and each declared parameter by its exact spelling; `Command`
  REPLACES on inherit) plus the optional `Presentation` group (below). `effectiveParam(spec, name)`
  is the ONE answer to "what does this parameter resolve to" - the reward's own value, else the
  declared default, else empty - so a command line, a chip label and an icon lookup cannot read one
  parameter differently. Id = filename. **The id convention is native-asset style, PascalCase with
  underscores**: the framework's own kinds are UNPREFIXED (`Item`, `Lootable`, `Stamped_Item`,
  `Effect`, `Droplist`, `Command`), a consumer's carry that mod's prefix (`Mmo_Xp`, `Mmo_Boost_Token`), so two mods
  installed together cannot collide by accident. A `$`-prefixed key is authoring metadata the codec
  ignores, on the file and inside `Params` alike - and a shipped `$Comment` is read by whoever opens
  the file next, so it says what the reward does and what each parameter means in game, never how the
  file came to look this way.
- **`RewardKindAsset.Presentation`** - how a kind's rewards READ where one is SHOWN before it is
  granted, written once on the kind instead of on every reward. Three independent leaves.
  `NameKey` is a TEMPLATE: each `{Param}` is replaced by that parameter's value, LOWER-CASED, so
  `"mymod.reward.xp.{Skill}"` with `Skill: "MINING"` asks for `mymod.reward.xp.mining` - which is
  what lets one line label a whole family, and what bridges a value written the way a command reads
  it (`ARTILLERY`) to a key written the way keys are written. A placeholder naming nothing declared
  is LEFT STANDING, exactly as the command template leaves one. `Args` names what fills the key's
  `{0}, {1}, ...` blanks, in order (modeled on `FeedbackMomentAsset.Line.Args`, so an author meets
  one idea twice): an entry naming a declared parameter binds that parameter's value - as a NUMBER
  when it reads as one, so a `{0, number}` blank groups digits in the player's own locale - an
  entry carrying a `.` or a `{` is a localization-key TEMPLATE (the same `{Param}` filling as
  `NameKey`) bound as a nested client-translated name, which is how a `Skill` parameter renders as
  the translated word for mining rather than the literal `MINING`, and an entry that is NEITHER
  (almost always a mis-spelled parameter name) is DROPPED - its blank renders empty rather than as
  a raw token, exactly the refusal its `FeedbackMomentAsset` sibling makes, and the validator's
  `SUSPECT_ARG` reports it; unauthored, the one `{0}` is the reward's amount.
  `Icon` is a nested rule
  `{Default, ByParam, Values}`: no `ByParam` means one item for every reward, `ByParam` names the
  parameter whose value picks one out of `Values` (matched case-insensitively), and anything
  unmapped takes `Default`, so a table names only the cases worth distinguishing. **Aim a `NameKey`
  at a key family that already exists** wherever there is one - a kind paying out something that is
  already named somewhere (a currency, an unlockable) points at that thing's own name key and ships
  no translations at all, and the next pack adding one of those things then works with no authoring.
  `Values` merges per VALUE under `Parent`, which is how a pack EXTENDS a mapping table; re-shipping
  the kind's own id REPLACES the kind, map and all. Resolution lives on the asset
  (`presentationNameKey` / `presentationIcon`, both over the shared `fillKeyTemplate`) so it is one
  answer, unit-testable with no store.
- **[`RewardKindConfig`](RewardKindConfig.java)** - the `defaults < pack < owner` table, like every
  other keyed type. What is in it is not yet payable; the fold is what makes it so.
- **[`CommandRewardKind`](CommandRewardKind.java)** - the handler: resolve the template, run it
  through [`command/CommandRunner`](../../command/CLAUDE.md) as the server console (so the `/give`
  positional-quantity fix rides along), and THROW when a `Required` parameter went unanswered.
  `retryCommand` is the same resolved line, and is null for exactly the specs that could not be
  granted - a reward that cannot say what it pays is not replayable either. `resolve` is public so a
  preview, an admin listing and the retry all read one answer instead of three spellings.
- **[`RewardKindFold`](RewardKindFold.java)** - `foldInto(kinds)`, called AFTER every Java
  registration once the stores have loaded. **JSON WINS**: an authored id replaces a Java-registered
  one, because an owner's file must not be overrulable by a mod. It is never silent - one boot WARN
  per shadow plus a `Result.shadowed()` the audit reports - because the swap costs that kind's engine
  services (the ask-first inventory fit check, any retry richer than re-running the line). It is
  **exactly one** warn: the registration goes through `registerQuietly`, so the ledger's generic
  "two owners wanted this id" line never lands above the one that explains the swap. A kind
  naming no command is SKIPPED, never registered: shadowing a working kind with a dud is strictly
  worse than not shadowing. A RE-FOLD (an asset re-import) shadows nothing: the fold recognises its
  own handlers, so a reload never reports the whole catalogue as having taken something over.
- **[`RewardKindValidator`](RewardKindValidator.java)** - domain `rewardkind`. `auditAll` over the
  kinds (`NO_COMMAND`, `UNKNOWN_PARAM` for a placeholder nothing fills, `UNUSED_PARAM`,
  `REQUIRED_WITH_DEFAULT`, `SUSPECT_ARG` for a `Presentation.Args` entry that names no declared
  parameter yet does not look like a localization key, and a guarded `UNKNOWN_COMMAND` head check
  off the engine command
  registry), `auditSpec` over a reward written for one (`UNKNOWN_PARAM`, `MISSING_REQUIRED_PARAM`),
  and `shadowed` for the INFO marker. The head check reads `CommandManager` directly - an engine
  type, not another module, so it costs no edge - and answers null rather than a finding wherever
  nothing can say which commands exist, since a check that cannot run must never invent one.

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
- **The authored fold runs LAST, and it is loud.** Never move `RewardKindFold.foldInto` ahead of a
  consumer's Java registrations to "avoid the shadow warning" - the ordering IS the rule that lets an
  owner's file win, and the warning is the only place they learn what it cost.
- **The declared schema is the authority on what a kind reads.** A parameter a kind does not declare
  reaches no command line, deliberately: substituting whatever a reward happened to write would make
  every authored kind's real surface unknowable, and the validator reports the stray parameter
  instead.
- Tests are mechanics and invariants: `RewardGrantsTest` (isolation, retry queueing, unhandled kinds,
  the typed parameter reads, the offline drop and the source stamp), `RewardJsonTest` (what becomes
  the kind, what becomes a parameter, what the dialect gets to change, and that a refusal lands at
  load with the file named), `FrameworkKindFailLoudTest` (an unpayable spec is reported failed, never
  granted), `RewardKindRegistryTest` (empty start, registration, lookup),
  `RewardKindFoldTest` (the two facets keyed together, and both compact grammars reading one table),
  and `DroplistRewardKindTest` (the whole parameter fold - which id, how many rolls, where they land -
  since every one of those is a way a payout could quietly go somewhere nobody meant it to; the roll
  and the spawn themselves are engine calls the in-game pass covers). For the authored kinds:
  `RewardKindAssetCodecTest` (the decode, the three DIFFERENT inherit rules - `Params` merges per
  parameter, `Presentation` per leaf and its `Icon.Values` per value, `Command` replaces - plus the
  presentation resolution: a template filled in and lower-cased, an undeclared placeholder left
  standing, an icon map hit, miss and case-insensitive match),
  `CommandRewardKindTest` (every substitution case plus the refusals,
  through a recording dispatcher), `RewardKindAssetFoldTest` (JSON wins, the one warning, the skipped
  dud, a re-fold shadowing nothing, and the `defaults < pack < owner` layering), `RewardKindValidatorTest` (one case per finding,
  and the quiet cases - above all a command-head check that skips rather than guesses).
