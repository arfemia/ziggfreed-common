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

The bounds-less form is the one to keep in mind: it is how "only where that mod is installed" is
written, so a zero DEFAULT would spring it open in precisely the case it exists to close. That is
why [`FactorProvider`](FactorProvider.java) returns a nullable `Double` rather than a `double`, and
why nothing in this package substitutes a value for an absent one.

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
  **[`DerivedFactorSource`](DerivedFactorSource.java)** - a factor DEFINED as a formula, with no
  Java: `Server/ZiggfreedCommon/Factors/<id>.json`, `{Formula: {...}}`, **the file name IS the factor
  id**. The config is the process-wide `defaults < pack < owner` fold and the shipped
  `DerivedFactorSource`; **every `FactorRegistry` starts wired to it**, so a derived id resolves in
  a placement gate, a dialogue condition, and another formula's term alike (clear the hook with
  `derivedSource(null)` for a private vocabulary). Nothing downstream can tell a derived id from a
  registered one - which is the point, and also why a bounds-less gate on one is a presence check on
  the DEFINITION rather than on its inputs.
- **[`DerivedFactorValidator`](DerivedFactorValidator.java)** - the load-time audit for the silent
  cases: `EMPTY_FORMULA`, `SELF_REFERENCE`, `CYCLE` (a static BFS over the definition graph),
  `NON_FINITE`, `CLAMP_INVERTED` as errors; `UNKNOWN_FACTOR` and `BLANK_TERM` as warnings. An unknown
  term id is only ever a WARNING: its owner may register later, or may be a mod the author expects
  some servers not to install, which is the value side working rather than a broken file. Reports
  shared [`validation.Finding`](../validation/CLAUDE.md) values under domain `factor`;
  `DerivedFactorConfig.audit([registeredElsewhere])` audits the folded pool and `logFindings()` is
  the always-on baseline (via `ValidationReport.logAll`).
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
- **[`FactorConditions`](FactorConditions.java)** - the ONE array evaluator:
  `firstFailure(conditions, registry, ctx)` returns the first failing factor id (so the caller can
  name it in the gate reason), `pass(...)` is the boolean wrapper, both in `List` and array form. A
  BLANK entry (no factor id) is SKIPPED rather than failing - a half-authored line is an authoring
  slip, and hiding working content behind it makes that slip much harder to find than a validator
  finding does. Each entry is re-scoped with its OWN `Param`, so two entries can address one factor
  differently.

## The portable standard library (zc-entity)

- **[`HytaleFactors`](../../../../../../../zc-entity/src/main/java/com/ziggfreed/common/factor/HytaleFactors.java)** -
  `registerInto(registry, owner)` claims eight `hytale:` ids, all straight reads of NATIVE engine
  data about the context's own subject: `stat` (Param = a registered `EntityStatType` id, answering
  its EFFECTIVE folded max), `tool_power` (Param = a native `GatherType`; omit for the best of any
  type), `tool_tier` (same Param contract as `tool_power`, a DIFFERENT native field - see below),
  `tool_durability_percent`, `tool_quality`, `tool_item_level`, `held_tag` (Param =
  `family:value` or a bare value), `held_item` (Param = an item id). **The namespace names the
  vocabulary's OWNER, not the registrant** - two mods converging on `hytale:tool_quality` is
  agreement rather than a collision, and an author can tell portability from the id alone.
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

## Consumers today

- **`npc.placement`** - `NpcPlacementAsset.Requires.Conditions` is a `FactorCondition[]`, evaluated
  by `PlacementFactorRegistry.firstFailure` with the placement id as the context PAYLOAD and no
  subject (a placement gate is asked before anything stands there to ask about).
- **`dialogue`** - the generic `{"Type":"Factor", Factor/Param/Min/Max}` condition, resolved against
  the registry the engine was built with (`DialogueEngine.Builder#factors`); store + subject are
  both the player. An engine built with NO registry fails every `Factor` condition closed with one
  warn, so a server missing the vocabulary's owner sees the ungated conversation.
- **a CONTRIBUTING mod** - one that registers ids through `FactorContributions` rather than reading
  any vocabulary of its own, so its numbers reach every consumer's content with no edge in either
  direction (a mob-difficulty mod publishing rarity / difficulty / region readings is the shape).

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
covers the findings. `zc-entity`'s `HytaleFactorsTest` pins the no-subject behaviour of every
portable id (null, never zero, never a throw), and `entity/ToolPowerSelectionTest` +
`entity/ToolTierSelectionTest` (mirror-image files, one per native field) each pin their own
selection contract over a FIXTURE multi-gather-type tool (the spec fold and its
lowercasing/strongest-wins rules, the named pick, case-insensitivity, absent-type null, and the
no-Param aggregate max) - pure cores, so neither needs a server. The remaining live-subject paths (a
real hotbar, a real item asset) need a running server and land behind in-game smoke, matching the
rest of the library's split.
