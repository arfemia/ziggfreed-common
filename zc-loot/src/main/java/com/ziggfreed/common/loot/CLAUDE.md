# loot/ - the one loot core

One core serves every drop site (`Roll`, `LootRef`, `Lootable`), so identical JSON behaves identically wherever it is authored.

- A `Roll` reads `Conditions`, then `Chance`, then `Ladder`: a failed condition consumes no chance sample, a failed chance never evaluates the ladder, and the top-level and floor grants stack.
- `Chance` is a `FactorFormula` group read as a percent held in 0..100 (`{"Base": 35}` is 35 percent), never a bare number: a bare scalar reads as absent, so the roll always fires and `LootableValidator` cannot see it. Check the shape, not the number.
- `Rolls` and `Pool.Entries` replace whole under `Parent`. To add to another table, name it in `ContributesTo`: id layering runs first and contributions fold on top, so `LootableConfig.resolve` answers the enriched table while `all` and `resolveAuthored` answer the files as written.
- Each table a `LootRef` names keeps its own pool, since merging two bags would change both tables' odds. Picks draw with replacement, and `LootPool.MAX_PICKS` is an anti-runaway ceiling, not a balance knob.
- Deciding and doing are separate calls: `LootEngine.select` is pure and `rollAndGrant` applies it. A payout made later (a spoils screen, a claim) keeps its `select` answer, so what was shown is what is paid.
- Keep `RollEvaluator` pure: a new side effect is a new `LootEngine.Sinks` seam. `Cue` is an opaque id, and this layer plays nothing.
- A weighted pick goes through zc-core's `util/WeightedPick`. Build one `FactorSnapshot` per moment and never hold it across moments. `FactorGate` walks a `Conditions` array, but the bound test is `FactorCondition.accepts`: never re-implement `Min`/`Max` here.
- `LootableValidator.UNKNOWN_CONTRIBUTION_TARGET` is INFO, not WARNING: a `ContributesTo` waiting on a table another mod ships is the leaf working as designed.
