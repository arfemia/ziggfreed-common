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
  stamper never re-derives a cap. `describe(StatRoll)` answers how one written stat READS.
- **[`StackStatsStamper`](StackStatsStamper.java)** - THE stamper, not merely a default (maintainer
  ruling 2026-09-01: stamping is ZC-DRIVEN, no consumer registers one). Stats live in the stack's own
  metadata, so a stamped item IS the record; stamps travel through a trade or a chest, and a stamped
  sword is never a new item ASSET. It also draws the tooltip in the SAME call, so a stamped item can
  never carry stats its description does not show.
  **[`DefaultStatNames.DURABILITY`](DefaultStatNames.java) is the one id that never reaches the
  record**: it raises the stack's max durability instead, because durability is a property of the
  item rather than a stat channel and there is nothing for an equip bridge to modify. The roll math
  never learns the difference, so a pool rolls it against the same budget as everything else.
- **[`StampTooltip`](StampTooltip.java)** - the ONE renderer: base prose, an "Enhancements" heading,
  one line per stat. It is here rather than in a consumer because an item's description slot holds
  exactly one thing, so two mods each writing "the" tooltip would be two mods overwriting each other.
  Its base-description GATE is the load-bearing part: **this surface has no markup parser**, so an
  item whose own description carries markup gets no base prose at all rather than visible angle
  brackets, and every candidate key is existence-checked first so a descriptionless item never prints
  its own key at a player.
- **[`StatNamer`](StatNamer.java)** + **[`StatNamerRegistry`](StatNamerRegistry.java)** +
  **[`DefaultStatNames`](DefaultStatNames.java)** - what a stat is CALLED, resolved in four tiers:
  an authored `StatDisplays` file, then the registered consumer vocabulary, then the CLIENT's own
  `client.itemTooltip.stats.<StatId>` label, then the id printed plainly. **A mod adding a stat
  registers nothing** - the client already names every stat it can show, so registering a `StatNamer`
  is for wanting something MORE (a colour, different wording), never for wanting a name at all.
  Every call is no-throw and falls through, so a broken vocabulary costs one stat's wording and never
  the line.
- **[`StatDisplayAsset`](StatDisplayAsset.java)** + **[`StatDisplayConfig`](StatDisplayConfig.java)** -
  `Server/ZiggfreedCommon/StatDisplays/<StatId>.json`, `{Key, Color}`, filename = stat id. Authored
  naming OUTRANKS a mod's compiled vocabulary, so a server owner or a pack can reword or recolour any
  stat without waiting on the mod that invented it.
- **[`StamperRegistry`](StamperRegistry.java)** - exactly ONE, last registration wins outright. Two
  stampers would mean two item formats, and then every budget check reads half the history and the
  ceilings quietly stop working. The wiring root registers `StackStatsStamper`, and **NOTHING should
  replace it** - a consumer wanting richer behaviour fills `StatNamer` instead of registering a
  second format.

## Rules to keep

- **A stat id is opaque to the ROLL MATH.** `StampCapEngine` rolls numbers and enforces budgets and
  must never branch on a stat id. The one id with a meaning, `DefaultStatNames.DURABILITY`, is
  recognised at the WRITE and nowhere else, which is precisely why the cap engine still treats it as
  an ordinary entry. What a stat MEANS otherwise belongs to whatever bridges the entries onto a live
  entity, and what it is CALLED belongs to `StatNamer`.
- **Never bypass the registry to write stats.** A second write path is a second format.
- **The pure core takes a lookup and a sample source**, never a registry or an RNG of its own, which
  is why every case below is testable with no server.
- Tests: `StampCapEngineTest` - choosing (Picks default, `Always` beside a draw, `Unique`, a parked
  entry), rolling (range bounds, factor scaling, a value that rounds to nothing), and above all the
  ceilings (lowest-budget-binds, prior points counted, budget spent once across entries, per-stat,
  the denial, and a re-stamp against a fake stamper). `StamperDescribeTest` pins the one thing a
  default method can silently break: a stamper that overrides nothing still ANSWERS `describe`, and
  answers null, so a caller's fallback is the documented path rather than an exception.
  `StampTooltipGateTest` - the base-description gate, pure: a key that does not exist and a key whose
  value carries markup both yield no base prose, and a generated-tool item nests its authored
  markup-free prose instead.

## Evidence

The engine provides NONE of this: no native per-instance stat application, and no enchant / affix /
socket / reforge mechanism anywhere in the server source or the 114 asset schemas. Audited across the
shared source by three scouts, with the metadata mechanics that bite (an empty document deletes its
own key; an undecodable one THROWS; reads are never cached) written up in
`.claude/research/native-item-enhancement-audit.md`.
