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
- **[`LootGrants`](LootGrants.java)** - what a roll or a pool entry hands over, four independent
  leaves: `Items[{Item,Count,CountMax}]` (exact, needs nothing else installed; `CountMax` makes the
  quantity vary evenly between the two, drawn when the payout is DECIDED rather than when it lands so
  a preview cannot show one number and hand over another), `DropLists[]` (native `ItemDropList` ids,
  each rolled INDEPENDENTLY in authored order), `Commands[]`, and `Rewards[{Kind,Params}]` (the open
  door to anything a mod registered).
- **[`LootPool`](LootPool.java)** - the other half of a table, and the opposite shape to `Rolls`:
  `{Rolls, Entries[{Weight, Conditions, Grants}]}`, a bag of COMPETING outcomes of which only as many
  as `Pool.Rolls` works out to are drawn. `Pool.Rolls` is the ordinary `FactorFormula`, so "one more
  pick per 1200 points" is a term weighted `1/1200` rather than a bespoke field, and the ceiling is an
  ordinary `Clamp.Max`. Three rules worth knowing: picks draw WITH replacement (three picks can hand
  over one entry three times), the count is taken DOWN to a whole number within
  `PICK_ROUNDING_TOLERANCE` (a weight of one-over-N cannot be held exactly by a double, and a player
  sitting on the threshold must not lose the pick they were promised), and `MAX_PICKS` is an
  anti-runaway ceiling rather than a balance knob. A pool cannot name a `Trigger`; it is drawn on the
  site's DEFAULT moment.
- **[`LootRef`](LootRef.java)** - `{Lootables[], Rolls[]}`, the ONE way content says "loot happens
  here". Both leaves resolve together, tables first. Each referenced table keeps its OWN pool rather
  than the pools being merged - pouring two bags together would change the odds inside both.
- **[`LootableAsset`](LootableAsset.java)** + **[`LootableConfig`](LootableConfig.java)** - the named
  table, `Server/ZiggfreedCommon/Lootables/<Name>.json`, id = filename lower-cased, folded
  `defaults < pack < owner`. `CHILD_ASSET_CODEC` lets a reference leaf take an inline body instead.
  **`Rolls` and `Pool.Entries` both REPLACE on inherit** - a child that authors any roll discards
  every inherited one. **`ContributesTo` is how you ADD**: it names another table's id, and this
  file's rolls and pool entries are folded into it on top of what it already has. Resolution order is
  the ordinary id layering FIRST, contributions after, so an owner overriding a table keeps every
  contribution other packs made to it and a removed pack takes exactly what it added. How often the
  merged pool is drawn stays the TARGET's decision; a target declaring no pool of its own borrows the
  first contributor's `Pool.Rolls`, because otherwise a pool that exists only through contributions
  could never be drawn. `resolve` answers the ENRICHED table (so every reader sees the same thing);
  `all` and `resolveAuthored` answer the files as written, which is what the validator and the editor
  pick list want.

## The decision, and the doing

- **[`RollEvaluator`](RollEvaluator.java)** - PURE. `evaluate(roll, lookup, chanceSample)` ->
  `Outcome(hit, topGrants, floorGrants, topCue, floorCue)`; `effectiveChancePercent` is public so a UI
  showing the odds shows the SAME number the roll uses. Ladder rules live here and nowhere else:
  absent terms resolve to 0, `Min` omitted reads as 0 and a 0 floor IS reachable, and floors sharing a
  threshold resolve to the LAST authored one.
- **[`LootEngine`](LootEngine.java)** - the half that acts, entirely through SEAMS (`Sinks`: item sink,
  drop-list sink, command dispatcher + placeholders, reward registry + subject, retry queue, warn).
  A caller supplying none gets a full evaluation with no effects, which is what a preview wants.
  `Result` reports what LANDED, not what was attempted. **Deciding and doing are separate CALLS**:
  `select(rolls, pools, trigger, lookup, sample)` answers the ordered `Selected(grants, cue)` list a
  pass settled on and touches nothing, and `rollAndGrant` is that answer applied. A site that pays out
  LATER - an end-of-run spoils screen, a claim waiting for the player to walk back - calls `select`
  once while the inputs are known and keeps the answer, so what was shown and what was handed over
  cannot disagree. `resolve(ref, unknownSink)` answers a ref's rolls AND pools together;
  `resolveRolls` is the rolls-only form.
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
- **[`LootCues`](LootCues.java)** - where an EARNED cue goes when the granting site has no presenter
  of its own. ONE registered `Presenter` (`present(cueId, subject, sourceId)`), last registration
  wins outright (two presenters would celebrate the same cue twice - the stamp registry's rule),
  nothing installed by default and every edge quiet: no presenter means no presentation, a throwing
  presenter costs its own cue and never the grant. A site WITH a presentation of its own (a station,
  a spoils screen) keeps reading `Result.getCues()` itself; the shared `Lootable` reward path
  forwards its earned cues here, which is what makes a cue authored on a quest-rolled table do
  anything at all.
- **[`LootableValidator`](LootableValidator.java)** - domain `lootable`. It hunts the mistakes that
  produce SILENCE (a roll that can never fire, a tier out of reach, a table id nothing answers to),
  because those are the ones nobody reports until a player asks where their reward went. `auditAll`
  is the whole-store pass a consumer wires into its own startup audit, each file reported against
  its OWN id. One finding sits a tier below the library's usual unknown-id WARNING:
  `UNKNOWN_CONTRIBUTION_TARGET` is a NOTE, because a `ContributesTo` waiting on a table another mod
  ships is the leaf working as designed, and only a typo makes it a mistake.
- **[`LootEditorDataSets`](LootEditorDataSets.java)** - the Asset Editor pick lists, answered live off
  the running tables.

## Rules to keep

- **NEVER add an edge beyond zc-core.** No presentation, no world, no dialogue, no effects, no
  progression. `zc-progression` and `zc-instance` both depend on this module, so an edge to either is
  an immediate cycle. A grant that needs a capability from elsewhere is a registered reward KIND whose
  handler lives where the capability does (the wiring root registers the `Effect` kind for exactly
  this reason).
- **This layer never plays anything.** `Cue` is an opaque id the granting site maps to its own sound
  or toast. The smart-cue rule is enforced in `LootEngine` because only it knows what a grant actually
  produced: a cue with no grants beside it is pure presentation and always rides; a cue beside grants
  rides only once they produced something. Each cue is judged against ITS OWN grants group.
  `LootCues` does not bend this rule: it is a registered seam handing an already-earned cue id to
  whatever presenter a consumer installed, and with none installed it does nothing.
- **Decision stays pure, effects stay behind seams.** A new side effect is a new seam on `Sinks`, never
  a call inside `RollEvaluator`.
- **A weighted pick goes through [`util/WeightedPick`](../util/CLAUDE.md)** - never a local copy.
- **A pool ADDS a way to compose, it never becomes a second engine.** An entry's `Grants` is the same
  `LootGrants` a roll's is, its `Conditions` are the same `FactorCondition[]`, and its draw goes
  through the same `WeightedPick`. If a pool ever needs something a roll has, give the roll's version
  to both rather than growing a parallel vocabulary here.
- Tests: `RollEvaluatorTest` (the decision table), `LootEngineTest` (sinks + the smart-cue rule),
  `LootPoolTest` (the pick-count arithmetic incl. the threshold case, eligibility, drawing,
  determinism, varying quantities), `LootableContributionTest` (what an enriched table holds, whose
  decision each part was, and every finding an author gets), `LootableAssetCodecTest` (decode +
  `Parent`), `LootableValidatorTest` (one case per finding).

## Convergence note for whoever next owns zc-core `factor/`

`FactorGate` duplicates the ARRAY WALK of `factor/FactorConditions` (which takes a registry + context;
this one takes a lookup). Both delegate the actual bound test to `FactorCondition.accepts`, so the
semantics cannot drift - but a lookup-based overload on `FactorConditions` would let this class go
away entirely. That belongs to the leg that owns that file.
