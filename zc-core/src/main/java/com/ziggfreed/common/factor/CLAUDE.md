# factor/ - the shared namespaced factor + condition vocabulary

Router for `com.ziggfreed.common.factor`. ONE read-side vocabulary every engine in this library
gates and scales on: a mod registers a namespaced factor id and the number behind it, and authored
content addresses that id with no Java. The package SPANS two modules (the same way
`com.ziggfreed.common.asset` does): the model + registry + condition are `zc-core`, the portable
`hytale:` standard library is `zc-entity` because it reads engine entity/item data.

## THE ONE RULE: a gate never silently opens

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

## Types (zc-core)

- **[`FactorContext`](FactorContext.java)** - the immutable question, builder-built, every leaf
  independently nullable and ORTHOGONAL: `param` (the authored argument beside the id), `world`,
  `store` + `subject` (the entity the question is ABOUT - live world-thread handles, valid only
  inside the `resolve` call), and `payload` (the consumer's own opaque extension, e.g. a placement
  id). **Field-additive by design**: a new leaf is a new nullable field plus a builder method, so a
  provider written against an older shape keeps working - which is what lets one vocabulary serve
  sites as different as a pre-spawn placement gate (no subject at all) and a dialogue render (both).
- **[`FactorProvider`](FactorProvider.java)** - `@Nullable Double resolve(ctx)`. World thread,
  synchronous, never retains the context.
- **[`FactorRegistry`](FactorRegistry.java)** - **INSTANTIABLE per consumer**, the dialogue-engine
  paradigm rather than a shared mutable global: one instance is one vocabulary, fully populated at
  setup and only then handed to the engine that reads it, so there is no registration race and one
  mod's ids never leak into another's. Backed by [`../registry/RegistryLedger`](../registry/CLAUDE.md)
  for owner attribution + failure counting; `register`/`resolve`/`ids`/`isRegistered`/`info`/
  `clear`, ids matched case-insensitively, last write wins. A library engine whose CONTENT is
  process-wide may still put ONE instance behind a static facade (`npc.placement
  .PlacementFactorRegistry` does) - that is the facade's call, not this class's.
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
  `registerInto(registry, owner)` claims seven `hytale:` ids, all straight reads of NATIVE engine
  data about the context's own subject: `stat` (Param = a registered `EntityStatType` id, answering
  its EFFECTIVE folded max), `tool_power` (Param = a native `GatherType`; omit for the best of any
  type), `tool_durability_percent`, `tool_quality`, `tool_item_level`, `held_tag` (Param =
  `family:value` or a bare value), `held_item` (Param = an item id). **The namespace names the
  vocabulary's OWNER, not the registrant** - two mods converging on `hytale:tool_quality` is
  agreement rather than a collision, and an author can tell portability from the id alone.
- **The three TOOL axes are deliberately three** and none subsumes another: `tool_power` is the
  functional read but SATURATES across a family's upper tiers, `tool_quality` orders rarity tiers
  but cannot separate two tools inside one, `tool_item_level` separates same-tier tools but does not
  track rarity. Weighting them is the author's call.
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

## Tests

`zc-core`'s `FactorVocabularyTest` pins the whole fail-closed matrix, the accepts table, and the
array evaluator; `zc-entity`'s `HytaleFactorsTest` pins the no-subject behaviour of every portable
id (null, never zero, never a throw). The live-subject paths need a running server and land behind
in-game smoke, matching the rest of the library's split.
