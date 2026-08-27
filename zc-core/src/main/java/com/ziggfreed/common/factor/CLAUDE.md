# factor/ - the shared namespaced factor + condition vocabulary

Router for `com.ziggfreed.common.factor`. ONE read-side vocabulary every engine in this library
gates and scales on: a mod registers a namespaced factor id and the number behind it, and authored
content addresses that id with no Java. The package SPANS two modules (the same way
`com.ziggfreed.common.asset` does): the model + registry + condition are `zc-core`, the portable
`hytale:` standard library is `zc-entity` because it reads engine entity/item data.

## THE ONE RULE: a gate never silently opens - and its deliberate exception

Everything else here is a consequence of it. `null` is the ONLY "cannot answer", and it fails every
condition whatever the bounds say:

| situation | resolves to | a `Min: 1` gate | a bounds-less gate |
|---|---|---|---|
| nobody registered the id | `null` | fails | **fails** |
| the provider answered null | `null` | fails | **fails** |
| the provider threw | `null` (+ ledger failure, warn once) | fails | **fails** |
| the provider answered NaN/infinite | `null` | fails | **fails** |
| the provider answered `0.0` | `0.0` | fails | passes |

The last row is the one to keep in mind: a bounds-less gate PASSES on a `0.0` reading, so it is
NEVER how "only where that mod is installed" is written (`ModFactors`' `0.0` for an absent mod is
exactly this row - a bounds-less `hytale:mod_installed` condition passes whether the mod is
installed or not; `Min: 1` is required). That is why [`FactorProvider`](FactorProvider.java) returns
a nullable `Double` rather than a `double`, and why nothing in this package substitutes a value for
an absent one - a provider that defaulted an unanswerable reading to `0` instead of `null` would
spring every bounds-less gate on it open.

**The VALUE side inverts it on purpose.** A [`FactorFormula`](FactorFormula.java) term that cannot
resolve contributes `0` and the sum still produces a number. The two rules answer different
questions and each is the safe answer to its own: a gate asks "may this appear at all?", where the
conservative answer to "I cannot tell" is no; a formula asks "how much?", where refusing would let
one uninstalled mod's optional `+0.25` blank a multiplier, a price, or a reward count everywhere it
is used. A term is an ADDEND and the neutral addend is zero. If a value genuinely must not exist
without some factor, that is a GATE and it belongs in the surrounding `Conditions`, never in a term.
`Base` is the value when nothing else resolves; `Clamp.Min` is the floor it may never fall through.

## Types (zc-core)

- **[`FactorContext`](FactorContext.java)** - the immutable question, builder-built, every leaf
  independently nullable and ORTHOGONAL: `param` (the authored argument beside the id), `world`,
  `store` + `subject` (the entity the question is ABOUT - live world-thread handles, valid only
  inside the `resolve` call), `target` (the OTHER entity in the moment, the one it happened TO), and
  `payload` (the consumer's own opaque extension, e.g. a placement id). **Field-additive by design**:
  a new leaf is a new nullable field plus a builder method, so a provider written against an older
  shape keeps working - which is what lets one vocabulary serve sites as different as a pre-spawn
  placement gate (no subject at all) and a dialogue render (both).
  - **`subject` and `target` are two leaves because a moment has two sides.** "How rare is the mob
    that died" and "how lucky is the player who killed it" are both readings of one kill, and a
    single entity leaf would force a provider to guess which one it was handed. A drop site threads
    the victim as `target`; a block break, a placement sweep and a dialogue line have none, so a
    target-reading provider answers null there and every gate on it stays shut - the standing rule,
    not a special case. `hasLiveTarget()` is the guard to ask before reading it, exactly like
    `hasLiveSubject()`.
  - **`withParam` carries every leaf.** The array evaluators rebuild the context per entry so each
    carries its own `Param`; a leaf dropped there would silently blank a factor for every entry after
    the first, which is why `FactorContextTest` pins the carry-over rather than trusting it.
- **[`FactorProvider`](FactorProvider.java)** - `@Nullable Double resolve(ctx)`. World thread,
  synchronous, never retains the context.
- **[`FactorFormula`](FactorFormula.java)** - the ONE authored VALUE leaf, `{Base?, Factors:[{Factor,
  Param?, Weight?}], Clamp?:{Min?, Max?}}`: a base plus a weighted sum of readings, clamped. Same
  FACTORY-codec shape as `FactorCondition` (the dropdown lands on a term's `Factor`), every leaf at
  every level `appendInherited` so a `Parent` file may override one group. `evaluate(registry, ctx)`
  is the normal path; `evaluate(lookup)` / the static `sum(terms, lookup)` are the thread-through
  form for an engine that resolves factors its own way. An EMPTY formula (no `Base`, no usable term)
  is not a constant zero - consumers treat it as no definition at all.
- **[`DerivedFactorAsset`](DerivedFactorAsset.java)**, **[`DerivedFactorConfig`](DerivedFactorConfig.java)**,
  **[`DerivedFactorSource`](DerivedFactorSource.java)** - one `Server/ZiggfreedCommon/Factors/<file>.json`
  file doing either or both of two jobs with no Java: DEFINING a factor id as a `Formula` (**the
  file name IS the factor id** then), and NAMING a factor for every surface that explains a
  requirement on it. The config is the process-wide `defaults < pack < owner` fold and the shipped
  `DerivedFactorSource`; **every `FactorRegistry` starts wired to it**, so a derived id resolves in
  a placement gate, a dialogue condition, and another formula's term alike (clear the hook with
  `derivedSource(null)` for a private vocabulary). Nothing downstream can tell a derived id from a
  registered one - which is the point, and also why a bounds-less gate on one is a presence check on
  the DEFINITION rather than on its inputs. `definedIds()` is the vocabulary listing (file ids that
  define a value); `ids()` would leak arbitrarily-named naming overlays.
  - **The NAMING half**: `Factor` (the target id, explicit because ids carry colons a filename
    cannot - the file may be named anything, and may target a factor a mod registered in CODE, the
    `NpcIdentityAsset`-style overlay), `Param` (narrows `Text` to one factor+param pair), `Text`
    (the shared `ContentTextAsset` group; the key surfaces resolve is `TitleKey`, `DisplayName` the
    plain fallback), and `ParamNames` (`KeyPattern` with a `{param}` slot + bespoke per-param
    `Keys`, plus two orthogonal optional pattern transforms: `StripPrefix` removes a declared
    prefix from the requirement's `Param` before substitution and `Case` (`Lower`/`Upper`,
    `Locale.ROOT`) folds it after the strip - the bridge from a technical channel spelling like
    `MMO_Level_MINING` to a key family registered as `...skill.mining`; neither transform touches
    a `Keys` entry, which matches the `Param` as authored). **Keys are written IN FULL and never namespaced for the author** (rules R0), so an
    overlay may point at any mod's shipped key - a station mod naming something with an MMO key, a
    pack reusing a library key. `Factor` and `Formula` are mutually exclusive (validator error); a
    naming-only file registers NO value, so it can never shadow the real provider of the id it
    names.
  - **Overlays COMPOSE, most specific first** ([`FactorNames`](FactorNames.java), the ONE walk and
    deliberately the ONLY naming mechanism - there is no Java naming seam on `FactorProvider`, so
    there is one place to look): an exact `Param` claim (the file's own `Param`, or a `Keys`
    entry), then every `KeyPattern` filled with the param, then a bare `Text` on the factor. A
    pattern whose resolved key is not shipped (probed through the one `i18n/LangCatalog`) is
    SKIPPED and the walk continues - which is what lets several mods each name their own params of
    one shared factor (`hytale:stat` being the worked case). A factor no file names answers null
    and the surface falls to its generic requirements line - the visible cue to author an overlay.
  - **The library ships overlays for its own nine `hytale:` factors** (zc-core resources,
    `Factors/Hytale_*.json`): `hytale:stat` in the pattern form
    (`ziggfreedcommon.progress.factor.stat.{param}`, the engine's own channels named in the nine
    `ziggfreedcommon.progress.lang` files), `hytale:held_item` patterned straight onto
    `server.items.{param}.name`, the rest with a bare `Text`. zc-progression ships two more for
    its own `ziggfreedcommon:quest_completed` / `achievement_earned` ids (bare `Text`; a condition
    naming a specific quest or achievement already reads with that content's own title through
    `quest.LockReasons`).
- **[`DerivedFactorValidator`](DerivedFactorValidator.java)** - the load-time audit for the silent
  cases. `validateAssets` is the file-level walk the config's `audit()` runs: `FACTOR_AND_FORMULA`
  (both halves at once - the finding names which leaf to remove for each intent) and
  `EMPTY_FORMULA` (a file that defines nothing and names nothing) as errors, `NAMES_NOTHING` (an
  overlay with no `Text` and no `ParamNames`) as a warning, a naming-only file VALID with no
  formula at all; every defining file then takes the formula checks - `SELF_REFERENCE`, `CYCLE` (a
  static BFS over the definition graph), `NON_FINITE`, `CLAMP_INVERTED` as errors, `UNKNOWN_FACTOR`
  and `BLANK_TERM` as warnings. An unknown term id is only ever a WARNING: its owner may register
  later, or may be a mod the author expects some servers not to install, which is the value side
  working rather than a broken file. Reports shared
  [`validation.Finding`](../validation/CLAUDE.md) values under domain `factor`;
  `DerivedFactorConfig.audit([registeredElsewhere])` audits the folded pool and `logFindings()` is
  the always-on baseline (via `ValidationReport.logAll`).
- **[`FeatureFlags`](FeatureFlags.java)** - the generic FEATURE-FLAG factor: a mod declares its own
  feature ids and their live on/off state (`register(namespace, featureId, owner, supplier)`, an
  alias being the same supplier under a second id), and the first declaration of a namespace
  contributes `<namespace>:feature` process-wide through `FactorContributions`, so authored content
  anywhere gates on another mod's switches with no Java and no dependency edge. The reading: a
  declared feature is 1/0 off its supplier (read per evaluation, so a runtime toggle lands on the
  next check; a THROWING supplier is 0 with one warn), an UNDECLARED feature id is a definite `0`
  (a feature nobody declared is genuinely off, and the real number keeps the bounds-less presence
  form usable as "the declaring mod is installed" - the `ModFactors` precedent), a missing `Param`
  is unanswerable (`null`), and an undeclared NAMESPACE is just an uncontributed id failing closed
  as everything does. Additive as of 2.0.0; nothing in the library consumes it yet.

### The R6 audit: which factors are assets, which stay Java, and why

Any factor whose VALUE is expressible as a `FactorFormula` over existing factors belongs in a
`Server/ZiggfreedCommon/Factors/` asset, not in code. Audited against that rule, every factor this
library registers in code stays Java, each for the same structural reason - it READS something no
formula can reach - and gets a naming overlay instead:

- the nine `HytaleFactors` ids read live engine data off the context's subject (a stat fold, the
  held stack's tool spec/tags/durability, a permission check on the connection);
- `ModFactors`' `hytale:mod_installed` reads the engine's plugin table;
- `ProgressionFactors`' four `ziggfreedcommon:` ids read a player's stored quest/achievement
  records through the runtime's registered stores;
- `FeatureFlags`' `<namespace>:feature` reads a consumer's live config suppliers.

A new code registration should have to justify itself the same way; anything that is arithmetic
over these belongs in a Factors file.
- **[`FactorRegistry`](FactorRegistry.java)** - **INSTANTIABLE per consumer**, the dialogue-engine
  paradigm rather than a shared mutable global: one instance is one vocabulary, fully populated at
  setup and only then handed to the engine that reads it, so there is no registration race and one
  mod's ids never leak into another's. Backed by [`../registry/RegistryLedger`](../registry/CLAUDE.md)
  for owner attribution + failure counting; `register`/`resolve`/`ids`/`isRegistered`/`info`/
  `clear`, ids matched case-insensitively, last write wins. A library engine whose CONTENT is
  process-wide may still put ONE instance behind a static facade (`npc.placement
  .PlacementFactorRegistry` does) - that is the facade's call, not this class's.
  - **Two shared layers sit under every registry, consulted in this order and only on a local miss**:
    `FactorContributions` (below), then its `DerivedFactorSource`. So a consumer's own registration
    always wins, a contributing mod's claim beats an asset definition of the same id, and an id
    nobody supplies at any layer fails closed exactly as it always did.
  - On a derived HIT it ADOPTS the definition into the ledger under owner `asset:<id>` (so an admin
    listing names the file to edit and an evaluation failure is countable); the adopted provider
    re-reads the config every call, so a re-import needs no invalidation and a dropped file goes
    straight back to failing closed.
  - `isRegistered` / `ids` / `info` answer for CONTRIBUTED ids too, because a validator asking "does
    anything answer this?" must not report a cross-mod factor whose owner IS installed as unknown.
    `clear()` drops only this registry's own registrations: a contribution belongs to the mod that
    made it.
- **[`FactorContributions`](FactorContributions.java)** - the process-wide door a mod claims a factor
  id through, so EVERY vocabulary on the server can read it. One `register(id, owner, provider)` call
  at the contributing mod's `setup()`, and from then on every `FactorRegistry` resolves that id as if
  it had registered the provider itself.
  - **Why it exists**: a per-consumer registry is right for a mod's own readings and wrong for the
    cross-mod case. A mob-difficulty mod knows how rare the mob in front of you is; a loot table in a
    third mod wants that number. Without a shared door the reader would have to depend on the writer,
    and every pair of mods that wanted to compose would need a bespoke bridge.
  - **Nothing has to be wired in the other direction, and setup ORDER does not matter** - a registry
    consults this table live, so a vocabulary built before the contributor ran still resolves the id.
  - **An absent contributor changes nothing about how content behaves**: nobody registers the id, so
    a gate on it fails closed and a formula term on it adds zero. That is the standing rule, and it
    is what lets one authored file be correct on a server with the mod and on a server without it.
  - Namespace the id after the vocabulary's OWNER (`mmomobscaling:mob_rarity_tier`), the same rule
    the portable `hytale:` library follows, so an author can tell from the id alone which mod has to
    be installed. The first claim of an id logs one line naming the owner, so the boot log already
    carries the whole picture; `contributors()` is the same thing as data (owner to sorted ids), for
    an admin listing or a diagnostic that wants it rendered its own way.
  - A throwing contributed provider is counted against the CONTRIBUTOR, not against whichever
    vocabulary happened to ask.
- **[`FactorCondition`](FactorCondition.java)** - the ONE authored gate leaf,
  `{Factor, Param?, Min?, Max?}`, bounds inclusive and independently optional. **The codec is a
  FACTORY**, `codec(dataSetId)`, because every consumer wants its OWN Asset Editor pick list on the
  `Factor` field; `CODEC` is the no-dropdown instance. Only name a dataset your mod actually serves
  - an unserved id renders an EMPTY pick list, which is worse for an author than free text.
- **[`FactorConditions`](FactorConditions.java)** - the ONE array evaluator, in THREE shapes, all in
  `List` and array form. `firstFailure(conditions, registry, ctx)` SHORT-CIRCUITS and returns the
  first failing factor id (so the caller can name it in the gate reason); `pass(...)` is the boolean
  wrapper over the same walk; `allFailures(...)` walks the WHOLE array instead and returns every
  failing CONDITION, in authored order, for a caller listing everything still in the way rather than
  naming the next thing to go and do. It hands back the conditions rather than their factor ids
  precisely because a caller writing several sentences needs each one's own `Param` and bound, and
  looking a condition back up by bare id is ambiguous the moment one array bounds a factor twice.
  Pick by what the surface is for: a per-row boolean check in a loop wants `pass`, a detail panel
  wants `allFailures`. A BLANK entry (no factor id) is SKIPPED by all three rather than failing - a
  half-authored line is an authoring slip, and hiding working content behind it makes that slip much
  harder to find than a validator finding does. Each entry is re-scoped with its OWN `Param`, so two
  entries can address one factor differently.
- **[`ModFactors`](ModFactors.java)** - `hytale:mod_installed`, the one `hytale:` id that lives
  HERE rather than in zc-entity because it reads no entity at all: `Param` is another mod's
  `Group:Name` and the engine's own plugin table answers it. **The presence-check idiom**: `Min: 1`
  reads as "only where that mod is installed" and `Max: 0` as "only where it is NOT" - a
  BOUNDS-LESS condition is NOT the same as `Min: 1` here, because it passes on ANY finite reading
  including the absent-mod `0`, so it never actually gates on presence. An absent mod is a definite
  `0` rather than this package's usual unanswerable `null` precisely so `Max: 0` has something to
  match - a `null` would shut the `Max: 0` gate on the very servers it exists for - but that same
  `0` is why the bounds-less shortcut fails for this id where it works for every other one. A
  MALFORMED `Param` still reads `null`. Contributed once from the wiring root, so every vocabulary
  on the server resolves it with nothing to wire.

## The portable standard library (zc-entity)

- **[`HytaleFactors`](../../../../../../../zc-entity/src/main/java/com/ziggfreed/common/factor/HytaleFactors.java)** -
  `registerInto(registry, owner)` claims nine `hytale:` ids, all straight reads of NATIVE engine
  data about the context's own subject: `stat` (Param = a registered `EntityStatType` id, answering
  its EFFECTIVE folded max), `tool_power` (Param = a native `GatherType`; omit for the best of any
  type), `tool_tier` (same Param contract as `tool_power`, a DIFFERENT native field - see below),
  `tool_durability_percent`, `tool_quality`, `tool_item_level`, `held_tag` (Param =
  `family:value` or a bare value), `held_item` (Param = an item id), `permission` (Param = a
  permission node, answered by `PlayerRef#hasPermission`). **The namespace names the
  vocabulary's OWNER, not the registrant** - two mods converging on `hytale:tool_quality` is
  agreement rather than a collision, and an author can tell portability from the id alone.
- **`permission` is portable because permissions are the ENGINE's paradigm** - a node on the
  player's connection, declared in a manifest - not one mod's invention. It is the factor spelling
  of the same requirement a shared `Requires` block writes as its `Permission` leaf, and both
  bottom out in that one engine call, so a server sees ONE answer whichever way it is authored. It
  reads `null` (never `0`) with no subject AND for a subject that is not a player: an entity with no
  connection has no permissions to hold, and a definite `0` there would open a "must NOT hold this"
  bound for every mob in the world.
- **The four TOOL-shaped axes are deliberately four** and none subsumes another: `tool_power` is the
  functional read but SATURATES across a family's upper tiers; `tool_tier` is the per-job GATE the
  engine itself enforces before a spec's power counts at all (a spec below a block's required tier
  never damages it, whatever its power reads); `tool_quality` orders rarity tiers but cannot
  separate two tools inside one; `tool_item_level` separates same-tier tools but does not track
  rarity. Weighting them is the author's call.
- **`tool_power` and `tool_tier` are the ONLY two tool factors that take a `Param`, and that is the
  ENGINE's shape rather than a choice made here.** A tool's gather powers AND its harvest-tier gates
  are each one native `ItemToolSpec` field PER `GatherType`, and a real tool carries a whole spread
  of both (a hatchet's authored `Woods` power sits beside token powers for soils, rocks, and every
  ore tier; a pickaxe reaching a high tier on Rocks/Ore while having no spec at all for Woods), so
  neither question means anything until an author says which job is being asked about:
  `{"Factor":"hytale:tool_power","Param":"Woods"}` / `{"Factor":"hytale:tool_tier","Param":"Rocks"}`,
  both matched case-insensitively. Omitting `Param` (absent or blank) is the AGGREGATE form on
  either - the BEST value the tool has for any type, the portable "how good/how gated is this tool
  at all" read - which is right when the site does not care what the tool is FOR and wrong the
  moment it does (a pickaxe's aggregate and a hatchet's aggregate are the same kind of number and
  say nothing about chopping). A NAMED gather type the held tool has no spec for resolves **null**
  on EITHER axis, not `0`: cannot do this job at all is a different answer from does it badly
  (power) or is gated at the lowest tier (tier), so the term contributes nothing and the gate stays
  shut. `Quality`, `ItemLevel` and durability are each one value for the whole item (or, for
  durability, for the stack), so `tool_quality` / `tool_item_level` / `tool_durability_percent`
  have nothing to address within them and **ignore `Param`** - do not author one expecting it to
  narrow anything.
- **`tool_tier` and `tool_quality` read entirely different native fields; do not conflate them.**
  `tool_quality` is the held ITEM's own rarity - `Item#getQualityIndex()` resolved through the
  `ItemQuality` asset map, one value authored once for the whole item. `tool_tier` is the native
  `ItemToolSpec.Quality` integer authored PER gather type on the tool's own spec array - the exact
  gate `BlockHarvestUtils.getSpecPowerDamageBlock` compares against a block's required
  `Gathering.Breaking.Quality` before a spec's power counts at all (a spec whose `Quality` sits below
  the block's requirement is refused outright, regardless of `Power`). Two same-rarity tools can gate
  very differently by job - `tool_quality` cannot see that at all, which is exactly why `tool_tier`
  exists as its own id rather than reusing that one.
- **A consumer may re-register the SAME ids with its own resolution** in its OWN registry (a work
  session holding a tool snapshot rather than reading the live hand). Same vocabulary,
  context-appropriate answer - that is the point of the registry being per consumer.
- **[`../entity/HeldItemUtil`](../../../../../../../zc-entity/src/main/java/com/ziggfreed/common/entity/HeldItemUtil.java)**
  is the guarded read layer underneath (active hotbar stack, item asset, raw tags, tool powers,
  quality value, item level, durability percent). Same rule: null means "cannot tell", never zero.

## The progression readings (zc-progression)

- **[`ProgressionFactors`](../../../../../../../zc-progression/src/main/java/com/ziggfreed/common/progress/runtime/ProgressionFactors.java)** -
  four `ziggfreedcommon:` ids answering for THE shared progression runtime: `quest_completed`
  (Param = a quest id, 1 when the quest has been finished AND its reward collected - stored status
  `COMPLETED`; a quest waiting in `COMPLETED_UNCLAIMED` reads 0), `quest_completions` (Param = a
  quest id, the lifetime count a repeatable is gated on - CLAIMS, the same rule, so a parked run
  counts only once its reward is collected), `achievement_earned` (Param = an
  achievement id, 1 when earned, collected or not) and `achievement_points` (Param ignored, the
  earned points total a milestone gate is written against). The quest reading and the achievement
  reading differ on collection DELIBERATELY: a quest prerequisite is the thing a player is sent to
  go and finish, so it waits for the payout the same way the `Requires.Quests` leaf does, while an
  achievement is earned the moment its criteria are met and its claim is a separate courtesy. They
  live beside the runtime that answers them because that is the one progression a server has,
  whoever contributed to it.
- **They are CONTRIBUTED, not registered per consumer**: the wiring root calls `contribute()` once,
  so every vocabulary on the server resolves them - a storefront's `Requires`, a board, a placement
  gate, a dialogue condition, a loot roll - with nothing to wire. `registerInto` is the same four
  ids over somebody else's engine, for a consumer running a private one.
- **An id nothing knows reads `null`, never `0`.** A mistyped quest id answering `0` would read as
  "they have not done it" and OPEN a bounds-less gate, so the ladder is: the player's own RECORD
  first (a quest they finished still answers after the content is retired), then the CATALOGUE
  (known, not done: `0`), then nothing. `quest_completions` answers nothing where the store cannot
  remember completions at all, because reporting everybody as zero would pass a "fewer than N" bound
  for a player who had done it a hundred times.
- **A read never BUILDS the runtime** (it answers null until something else does), so a gate
  evaluated during a placement sweep or a content audit cannot seal the engines before every
  consumer has registered its parts.

## Levels, and every other consumer-mirrored number

There is no factor id for a level, and there deliberately never will be: a consumer that mirrors its
own numbers onto NATIVE stat channels gets them read by `hytale:stat` for free, with no vocabulary
of its own to register and no dependency for the content author. That is the authoring pattern for
this whole class of requirement:

```json
"Requires": { "Conditions": [
  { "Factor": "hytale:stat", "Param": "MMO_Level_MINING", "Min": 30 },
  { "Factor": "hytale:stat", "Param": "MMO_CombatLevel",  "Min": 60 } ] }
```

`Param` is the channel's registered `EntityStatType` id - a jar-bundled `Server/Entity/Stats/<id>.json`
or one a mod registers at boot - and the reading is its EFFECTIVE (folded max) value. A channel id
nothing registered fails closed with one named warn per id, which is what a typo in a `Param` looks
like. Mirror a derived scalar with
[`stats/StatMirror`](../../../../../../../zc-entity/src/main/java/com/ziggfreed/common/stats/CLAUDE.md)
and it is gate-able, scale-able and roll-able from that moment on.

## Consumers today

- **`npc.placement`** - `NpcPlacementAsset.Requires.Conditions` is a `FactorCondition[]`, evaluated
  by `PlacementFactorRegistry.firstFailure` with the placement id as the context PAYLOAD and no
  subject (a placement gate is asked before anything stands there to ask about).
- **`dialogue`** - the generic `{"Type":"Factor", Factor/Param/Min/Max}` condition, resolved against
  the registry installed into the shared engine's ONE factor slot (`DialogueEngine.installFactors`,
  first-install-wins); store + subject are both the player. With NOBODY installed, every `Factor`
  condition fails closed after one warn, so a server missing the vocabulary's owner sees the ungated
  conversation. The slot being singular is also why a mod CONTRIBUTES the ids its own dialogues gate
  on: the holder answers its own registrations plus the process-wide table, so a locally-registered
  id is unanswerable on a server where another mod installed first.
- **a CONTRIBUTING mod** - one that registers ids through `FactorContributions` rather than reading
  any vocabulary of its own, so its numbers reach every consumer's content with no edge in either
  direction (a mob-difficulty mod publishing rarity / difficulty / region readings is the shape).
  The library's own progression readings are contributed the same way, from the wiring root.
- **a shared `Requires` block** -
  [`progress/gate`](../../../../../../../zc-progression/src/main/java/com/ziggfreed/common/progress/gate/CLAUDE.md)'s
  `Factors` leaf is a `FactorCondition[]` over the consumer's registry, so a requirement on any
  content this library carries is the same numeric gate an NPC placement or a dialogue option is
  written with.

## Asset Editor pick lists

Both factor fields offer a dropdown, served by [`../asset/EditorDataSets`](../asset/CLAUDE.md) and
wired in `ZiggfreedCommonPlugin`: `ziggfreedcommon:placement_factors` on a placement's
`Requires.Conditions`, `ziggfreedcommon:factors` on a dialogue `Factor` condition and on a formula
term. Today both answer the SAME union (the placement facade's registered ids plus every derived
id), because that union is the whole of what a wiring root can enumerate; a factor a mod keeps in
its own per-consumer registry appears once it also claims it in the shared facade. Two ids all the same,
so the placement list can narrow later without moving every codec. **A dropdown is authoring
convenience, never validation**: hand-written JSON never passes through the editor, a free-typed id
still resolves, and the validators stay the real check.

## Tests

`zc-core`'s `FactorVocabularyTest` pins the whole fail-closed matrix, the accepts table, and the
array evaluator; `FactorFormulaTest` pins the value side's mirror-image degrade-to-zero table plus
the codec and `Parent` inheritance; `FactorContextTest` pins every leaf's absent-until-supplied
default, the two entity leaves' independence, and the `withParam` carry-over;
`FactorContributionsTest` pins the whole absent-mod story (uncontributed id resolves to nothing, the
bounds-less gate stays shut, the term adds zero while the rest of the formula survives) beside the
installed one, plus local-beats-contributed precedence and per-contributor attribution;
`DerivedFactorTest` runs an asset-defined factor end to end and
pins the two silent killers (a cycle fails closed ALL the way out rather than being swallowed by the
degrade-to-zero rule, and a definition reloaded away is not cached open); `DerivedFactorValidatorTest`
covers the findings. `zc-progression`'s `progress/runtime/ProgressionFactorsTest` walks each
progression ladder rung by rung over a double AND over the real engines on in-memory stores, and
pins the two rules a reader has to trust: an id nothing knows answers nothing (so a bounds-less gate
on a typo stays shut), and the `Quests` prerequisite leaf and `quest_completed` agree about one
player and one quest. `zc-entity`'s `HytaleFactorsTest` pins the no-subject behaviour of every
portable id (null, never zero, never a throw), with `permission` pinned on its own because it is the
one whose absent answer could be argued as a definite no, and `entity/ToolPowerSelectionTest` +
`entity/ToolTierSelectionTest` (mirror-image files, one per native field) each pin their own
selection contract over a FIXTURE multi-gather-type tool (the spec fold and its
lowercasing/strongest-wins rules, the named pick, case-insensitivity, absent-type null, and the
no-Param aggregate max) - pure cores, so neither needs a server. The remaining live-subject paths (a
real hotbar, a real item asset) need a running server and land behind in-game smoke, matching the
rest of the library's split.
