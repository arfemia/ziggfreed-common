# rotation/ - the deterministic rotating-pool primitive (module `zc-commerce`)

Router for `com.ziggfreed.common.rotation`. What is on show right now, and how it changes. Pure and
type-agnostic over the candidate type, so a board of contracts and a storefront's featured shelf run
ONE rotation rather than one apiece.

| Class | What it is |
|---|---|
| [`RotationSpec`](RotationSpec.java) | the cadence as two numbers, a period length and an anchor offset, over `util/PeriodMath`. `periodIndex` / `periodStartMs` / `millisUntilNext` / `nextRotationMs` / `samePeriod` |
| [`SelectionSpec`](SelectionSpec.java) | which registered strategy draws the set, and what the draw is keyed on |
| [`SelectionStrategy`](SelectionStrategy.java) | the algorithm itself; `WeightedRandom` and `All` ship |
| [`SelectionStrategies`](SelectionStrategies.java) | the open, process-wide table those two are seeded into |
| [`PoolSlot`](PoolSlot.java) | one position and what may fill it: a grade, a tag, a count, and whether it may come up empty. `accepts` is the ONE eligibility test |
| [`PoolSeed`](PoolSeed.java) | the one seed fold, plus a DISTINCT per-position form |
| [`WeightedSlotDraw`](WeightedSlotDraw.java) | the draw itself, plus `drawReplacement` for a single position |
| [`SlotRerollEngine`](SlotRerollEngine.java) | one player's overrides laid over the shared base draw, and the exclusion sets a reroll needs |
| [`RerollSpec`](RerollSpec.java) | what a reroll costs and how many a period allows |

## Rules to keep

- **Zero persisted state, and that is the point.** The active set is a deterministic function of
  `(poolId, period, seed)`: every player computes the same set, a restart recomputes exactly what
  was there, and there is no schedule anywhere to drift. Never introduce a stored "current set".
- **No clock lives here.** Every entry point takes `nowMs`, so a whole rotation's life is
  exercisable by handing it numbers.
- **The draw sorts by id before seeding.** That is what makes it reproducible whatever order the
  pool arrived in, and it is why a candidate's id is a required accessor rather than a convenience.
- **The per-position reroll seed is DISTINCT from the base seed.** Folding position and reroll count
  in AFTER the base mix is what stops a replacement colliding with the draw it is replacing.
- **`excludeAll`, never "exclude the others".** A deterministic draw allowed to re-pick the current
  candidate reads as "no alternative exists" whenever it lands there and stays stuck until the
  reroll count moves - a player paying for a reroll that visibly does nothing.
- **A stale override is DROPPED, not shown.** An override whose candidate no longer resolves, or no
  longer fits its slot, is discarded and the base pick stands, so editing a pool can never leave
  somebody looking at something that is gone.
- **An unknown selection type resolves to NOTHING, never the default.** Falling back is how a typo
  becomes a rotation that looks like it works and shows the wrong thing forever.
- **A weight of zero or less reads as one.** An unweighted candidate is an ordinary candidate; a
  weight is a bias, never an exclusion. Exclusion is what a slot filter is for.
- **These are RUNTIME values.** What an author writes is `commerce.asset.RotationAsset` and friends;
  nothing here carries a codec. Each domain's authoring layer keeps the word its own authors use for
  a grade (a contract's difficulty, an offer's tier) and folds it into `PoolSlot`'s one neutral
  `tier`, so the two sides differ in vocabulary without a second eligibility rule ever existing. The
  crossing itself is [`commerce/fold/CommerceFold`](../commerce/fold/CLAUDE.md), and it follows the
  AUTHORED leaf's documentation rather than the convenience defaults here: an unauthored cadence
  never turns over, and a cadence word this schema does not know never quietly becomes daily.
