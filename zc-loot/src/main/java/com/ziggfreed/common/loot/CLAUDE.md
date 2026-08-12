# loot/ - the ONE loot core (module `zc-loot`)

Router for `com.ziggfreed.common.loot`. Everything that decides "did something drop, and what" lives
here: a station's rare finds, a chest, a mob's extra drop, a quest reward that rolls a table. One
model, one evaluator, one set of rules, so identical JSON behaves identically wherever it is authored.

The module sits at the BOTTOM of the graph (zc-core only), which is what makes that possible - every
engine above can reach the loot core without any of them reaching each other.

## The model

- **[`Roll`](Roll.java)** - one conditional payout: `{Trigger, Conditions, Chance, Ladder, Grants, Cue}`.
  Read in that order, with two step-skipping rules that are load-bearing: a failed condition means the
  chance is never rolled (a gated roll consumes no sample), and a failed chance means the ladder is
  never evaluated (a rare tier cannot leak out of a roll that did not fire). Top-level and floor grants
  STACK. `Chance` is the shared `FactorFormula` read as a PERCENTAGE and held inside `0..100` whatever
  the terms say; `Ladder.Factors` is a bare `Term[]` because a ladder has no base and no ceiling, so a
  full formula there would be two dead knobs.
- **[`LootGrants`](LootGrants.java)** - what a roll hands over, four independent leaves:
  `Items[{Item,Count}]` (exact, needs nothing else installed), `DropLists[]` (native `ItemDropList`
  ids, each rolled INDEPENDENTLY in authored order), `Commands[]`, and `Rewards[{Kind,Params}]` (the
  open door to anything a mod registered).
- **[`LootRef`](LootRef.java)** - `{Lootables[], Rolls[]}`, the ONE way content says "loot happens
  here". Both leaves resolve together, tables first.
- **[`LootableAsset`](LootableAsset.java)** + **[`LootableConfig`](LootableConfig.java)** - the named
  table, `Server/ZiggfreedCommon/Lootables/<Name>.json`, id = filename lower-cased, folded
  `defaults < pack < owner`. `CHILD_ASSET_CODEC` lets a reference leaf take an inline body instead.
  **`Rolls` REPLACES on inherit** - a child that authors any roll discards every inherited one.

## The decision, and the doing

- **[`RollEvaluator`](RollEvaluator.java)** - PURE. `evaluate(roll, lookup, chanceSample)` ->
  `Outcome(hit, topGrants, floorGrants, topCue, floorCue)`; `effectiveChancePercent` is public so a UI
  showing the odds shows the SAME number the roll uses. Ladder rules live here and nowhere else:
  absent terms resolve to 0, `Min` omitted reads as 0 and a 0 floor IS reachable, and floors sharing a
  threshold resolve to the LAST authored one.
- **[`LootEngine`](LootEngine.java)** - the half that acts, entirely through SEAMS (`Sinks`: item sink,
  drop-list sink, command dispatcher + placeholders, reward registry + subject, retry queue, warn).
  A caller supplying none gets a full evaluation with no effects, which is what a preview wants.
  `Result` reports what LANDED, not what was attempted.
- **[`FactorLookup`](FactorLookup.java)** / **[`FactorSnapshot`](FactorSnapshot.java)** - readings come
  through a `(factorId, param) -> Double` lookup, so a test drives a roll off a fixture map, a whole
  batch shares ONE memoized snapshot, and an engine with its own resolution plugs straight in. Build a
  snapshot per moment and discard it; never hold one across moments.
- **[`FactorGate`](FactorGate.java)** - the array walk for `Conditions` over a lookup. The BOUND TEST
  itself is `FactorCondition.accepts` in zc-core, so a `Min`/`Max` means one thing everywhere. Nothing
  authored passes; an id-less entry is skipped; everything else fails CLOSED.
- **[`LootFactors`](LootFactors.java)** - `ziggfreedcommon:instance_score` / `:instance_win`, read off
  an `Outcome` payload on the context. They exist so a run's result stops being a special case: a
  score gate is now an ordinary condition, mixable with any other factor.
- **[`LootableValidator`](LootableValidator.java)** - domain `lootable`. It hunts the mistakes that
  produce SILENCE (a roll that can never fire, a tier out of reach, a table id nothing answers to),
  because those are the ones nobody reports until a player asks where their reward went.
- **[`LootEditorDataSets`](LootEditorDataSets.java)** - the Asset Editor pick lists, answered live off
  the running tables.

## Rules to keep

- **NEVER add an edge beyond zc-core.** No presentation, no world, no dialogue, no effects, no
  progression. `zc-progression` and `zc-instance` both depend on this module, so an edge to either is
  an immediate cycle. A grant that needs a capability from elsewhere is a registered reward KIND whose
  handler lives where the capability does (the wiring root registers the `effect` kind for exactly
  this reason).
- **This layer never plays anything.** `Cue` is an opaque id the granting site maps to its own sound
  or toast. The smart-cue rule is enforced in `LootEngine` because only it knows what a grant actually
  produced: a cue with no grants beside it is pure presentation and always rides; a cue beside grants
  rides only once they produced something. Each cue is judged against ITS OWN grants group.
- **Decision stays pure, effects stay behind seams.** A new side effect is a new seam on `Sinks`, never
  a call inside `RollEvaluator`.
- **A weighted pick goes through [`util/WeightedPick`](../util/CLAUDE.md)** - never a local copy.
- Tests: `RollEvaluatorTest` (the decision table), `LootEngineTest` (sinks + the smart-cue rule),
  `LootableAssetCodecTest` (decode + `Parent`), `LootableValidatorTest` (one case per finding).

## Convergence note for whoever next owns zc-core `factor/`

`FactorGate` duplicates the ARRAY WALK of `factor/FactorConditions` (which takes a registry + context;
this one takes a lookup). Both delegate the actual bound test to `FactorCondition.accepts`, so the
semantics cannot drift - but a lookup-based overload on `FactorConditions` would let this class go
away entirely. That belongs to the leg that owns that file.
