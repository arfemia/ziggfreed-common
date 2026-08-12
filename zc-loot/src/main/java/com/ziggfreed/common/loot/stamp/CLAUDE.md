# loot/stamp/ - rolling stats onto an item, and holding them down (module `zc-loot`)

Router for `com.ziggfreed.common.loot.stamp`. An anvil upgrading a sword, a reward handing over
pre-stamped gear, a chest producing a rolled trinket - all of them draw from a pool, roll point
values, and hold the result inside a budget. That is this package, and the arithmetic is entirely
separate from where the numbers end up.

## The model

- **[`StatRollEntry`](StatRollEntry.java)** - one candidate outcome: `{Stat, Points{Min,Max,Factors},
  Weight, Always}`. `Weight` and `Always` are independent and between them cover every roll shape
  anyone authors: weights alone is a lottery, all-`Always` is a fixed set, mixing them is "a
  guaranteed baseline plus a lucky extra". An `Always` entry costs NO pick and its weight is never
  consulted. **An absent `Weight` and a written `0` mean different things**: absent is ordinary, a
  written 0 is "never drawn", which is how an entry is parked without being deleted.
- **[`RollPoolAsset`](RollPoolAsset.java)** + **[`RollPoolConfig`](RollPoolConfig.java)** - the named
  pool, `Server/ZiggfreedCommon/RollPools/<Name>.json`, id = filename lower-cased, folded
  `defaults < pack < owner`. `Entries` REPLACES on inherit, like every table of this shape.
- **[`StampSpec`](StampSpec.java)** - a whole stamp: `{Pool, Entries[], Picks{Min,Max}, Unique, Caps}`.
  **No `Picks` authored draws ZERO** - deliberate, so a spec with only `Always` entries is fully
  predictable and one that forgot its Picks is visibly inert rather than quietly free.
  `Caps.Budgets[]` is the total ceiling and the one that BINDS is the LOWEST, which is what lets a
  hard absolute maximum sit beside a factor-scaled earned allowance; `Caps.PerStat` is a separate
  additional ceiling per stat id.

## The decision, and the write

- **[`StampCapEngine`](StampCapEngine.java)** - PURE, four steps: gather (pool then inline), choose
  (`Always` plus the lottery through the shared `WeightedPick`), roll (uniform in range plus factor
  terms, rounded), hold (ceilings applied in authored order, budget SPENT as it goes). Every ceiling
  is measured against what the item already carries, so budgets survive re-stamping.
- **[`StampPlan`](StampPlan.java)** - the answer. `NOTHING` and `DENIED` are DIFFERENT and a caller
  must not conflate them: nothing is a legitimate miss; denied means the item is full and everything
  rolled was cut away, which is the signal to abort before charging. That is what stops an item being
  farmed for nothing.
- **[`Stamper`](Stamper.java)** - the write boundary: `inspect` reads history, `apply` writes and
  returns a NEW stack (items are immutable). Entries arrive already held inside their budgets; a
  stamper never re-derives a cap. Plus the optional `describe(StatRoll) -> Message`: **the write
  stays display-blind, but the stamper may NAME what it wrote.** A stat's name belongs where its
  meaning does, and that is the stamper - it is the one object that knows `Swing_Speed` is "Attack
  Speed", in what colour, in the player's own locale. Whatever REPORTS an enhancement asks for it
  rather than inventing a vocabulary it does not own. The default answers null and a caller falls
  back to its own plain report (the stat id and its points), so a bare stamper still says what a
  ritual did.
- **[`StackStatsStamper`](StackStatsStamper.java)** - the default: stats live in the stack's own
  metadata, so a stamped item IS the record. Stamps travel with the item through a trade or a chest,
  and a stamped sword is never a new item ASSET.
- **[`StamperRegistry`](StamperRegistry.java)** - exactly ONE, last registration wins outright. Two
  stampers would mean two item formats, and then every budget check reads half the history and the
  ceilings quietly stop working. Nothing is installed by default; the wiring root registers
  `StackStatsStamper`.

## Rules to keep

- **A stat id is opaque here.** This package rolls numbers and enforces budgets. What a stat MEANS,
  and what wearing it does, belongs to the stamper and to whatever bridges those entries onto a live
  entity. Never branch on a stat id in this package.
- **Never bypass the registry to write stats.** A second write path is a second format.
- **The pure core takes a lookup and a sample source**, never a registry or an RNG of its own, which
  is why every case below is testable with no server.
- Tests: `StampCapEngineTest` - choosing (Picks default, `Always` beside a draw, `Unique`, a parked
  entry), rolling (range bounds, factor scaling, a value that rounds to nothing), and above all the
  ceilings (lowest-budget-binds, prior points counted, budget spent once across entries, per-stat,
  the denial, and a re-stamp against a fake stamper). `StamperDescribeTest` pins the one thing a
  default method can silently break: a stamper that overrides nothing still ANSWERS `describe`, and
  answers null, so a caller's fallback is the documented path rather than an exception.
