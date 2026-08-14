# cost/ - the one price, and the one authority that charges it (module `zc-commerce`)

Router for `com.ziggfreed.common.cost`. What something costs, and the only code that takes it.

| Class | What it is |
|---|---|
| [`Cost`](Cost.java) | currencies plus raw items, an `ALL`/`ANY` combine, and an optional growth curve. Immutable; `scaled` answers a NEW price |
| [`ItemCost`](ItemCost.java) | one raw-item component: an item id and a count of at least one |
| [`CostScaling`](CostScaling.java) | how a price grows per purchase: a curve discriminator plus a multiplier and a soft cap |
| [`CostEngine`](CostEngine.java) | `check` / `drain` / `refund`, plus `takeAllOrRollback` and a whole-shortfall listing |

## Rules to keep

- **ONE price vocabulary, and this is it.** A shop offer, a reroll, an unlock and anything priced
  later all charge through this. A terser "one currency and an amount" pair is exactly how a
  multi-currency reroll ends up impossible to author, so it does not exist.
- **Refund the RECEIPT, never the price.** An `ANY` price charges exactly one component, so
  refunding the price would hand back things nobody paid. That is why `drain` answers a
  `Receipt` rather than a boolean, and why `refund` takes the receipt's own cost.
- **An `ALL` price never half-charges.** Items are verified before any currency moves, currencies
  are taken with every earlier one put back the moment one fails, and items are removed LAST with
  the currencies compensated if they vanish in between. That ordering is the whole design; do not
  reorder it for convenience.
- **`check` names the FIRST shortfall** so a caller can say which thing is missing;
  `shortfall` names ALL of them, for a preview showing several components at once.
- **Scale ONCE, where the price is quoted.** A grown price keeps its curve so a preview can describe
  it, which means scaling an already-grown price compounds it. The purchase engine is the one place
  that applies it.
- **This is the RUNTIME value.** What an author writes is `commerce.asset.CostAsset`; nothing here
  carries a codec, and adding one would be a second spelling of a price.
  [`commerce/fold/CommerceFold.cost`](../commerce/fold/CLAUDE.md) is the crossing. Note what it
  CANNOT carry across: the authored group declares no growth curve, so a folded price never scales
  and `scaled` stays reachable only from Java, for a consumer quoting its own.
