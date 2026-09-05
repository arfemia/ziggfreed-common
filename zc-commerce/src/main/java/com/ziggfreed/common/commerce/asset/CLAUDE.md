# CLAUDE.md - `commerce/asset/` (module `zc-commerce`)

The AUTHORING groups every commerce type shares. Every class here exists because two or more of
Shops / ShopPools / ShopEntries / Boards / Bounties would otherwise declare the same fields twice and
drift apart one edit at a time.

Package root `com.ziggfreed.common.commerce.asset`. It imports zc-core only, and may never import a
commerce ENGINE package, because an authored group is what the engine reads, not the other way
round.

| Class | The group it declares |
|---|---|
| `CostAsset` (+ `.ItemCostAsset`) | `Cost`: `Currencies` / `Items` / `Combine`. The ONE price spelling in the library |
| `RotationAsset` | `Rotation`: `Period` / `Every` / `OffsetMinutes` / `Weekday` |
| `SelectionAsset` | `Selection`: `Type` / `Seed`, a union discriminator over the registered strategies |
| `SlotAsset` (+ `appendLeaves`) | the slot leaves every rotating set shares: `Count` / `Optional` |
| `RerollAsset` | `Reroll`: a full `Cost` plus `MaxPerPeriod` |
| `CommerceEditorDataSets` | the in-game editor pick-list ids the commerce codecs declare |

## Rules to keep

- **One price vocabulary, everywhere.** A reroll's price, an offer's price and anything else that
  charges are all `CostAsset`. A terse `{currency, amount}` pair is deliberately absent: two spellings
  of a price is how a multi-currency reroll ends up impossible to author, and it is exactly the kind
  of drift a shared group exists to stop.
- **`Period` and `Every` are two leaves, not a mode**, and authoring both is a validator ERROR rather
  than a silent precedence rule. Whichever one lost would be a number an author believes is doing
  something.
- **`appendLeaves` is the extension mechanism** for `SlotAsset`, mirroring the gate clause's and the
  objective leaf's. Each domain's slot codec starts from that call and adds the ONE word that domain
  filters on - a shelf's `Tier`, a board's `Difficulty` - so the structure cannot drift while each
  side keeps the word its own authors already use. `label()` is the single read a draw or an audit
  asks, so neither ever branches on which domain it was handed.
- **Every leaf is `appendInherited`**, so a file with a `Parent` retunes one number and keeps the
  rest. Adding a leaf without it silently breaks that for the leaf.
- **`Combine` is a union DISCRIMINATOR, not a mode**: it picks which of two payment routes applies and
  toggles nothing else. A word that is neither is reported rather than quietly becoming `All`.
- **A `$Comment` in any of these files is a TIP for the server owner or pack author.** The codecs skip
  `$`-prefixed keys, so an authored file can be documented inline.

## Adding to it

- A new field: a leaf in the group it belongs to, `appendInherited`, documented for an AUTHOR (what
  it does in game, what unauthored means). A cohesive pair or trio is a new nested group, never a
  flat prefixed key.
- A new group belongs here only when TWO of the commerce types want it. One type wanting it means it
  belongs to that type's own codec.
- **The Cost SEAM**: this is the authored shape only. The commerce engine builds its own runtime price
  value from `currencyAmounts()` / `itemCosts()` / `combinesAny()`, and nothing here imports it. The
  crossing lives in [`commerce/fold/CommerceFold`](../fold/CLAUDE.md), which is also where a new
  group's degrade behaviour is written down - so add the accessors an author's answer needs and let
  the fold decide what an unauthored or unreadable one means there.
- Tests are mechanics, structure, and invariants. Fixture content is author-owned; never assert
  numbers that belong to somebody's balance pass.
