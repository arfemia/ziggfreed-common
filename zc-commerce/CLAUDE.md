# CLAUDE.md - zc-commerce

The ECONOMY layer: what a currency is and how one is moved, the one price vocabulary and its
check/drain/refund engine, the deterministic rotation primitive, and the storefront and board
engines built on all three. Shops and boards live in ONE module because they share every part of
that list; splitting them would manufacture an edge between two halves of one domain.

## Build

Part of the thirteen-module `ziggfreed-common` build (`gradle/zc-module.gradle` convention,
Java 25, compiles as `:zc-commerce`). See the root [`CLAUDE.md`](../CLAUDE.md) for the aggregate
build.

## Dependencies

- **Depends on**: `zc-core` (SafeLog, `Subject`, `inventory/`, `counter/`, the factor model,
  `validation/`, `registry/`, the asset bases, `time/DurationGroup`, `util/PeriodMath`), `zc-loot`
  (the ONE reward vocabulary and the ONE issuance pass), `zc-progression` (bounties ARE quests, and
  every gate is the shared `progress.gate` evaluator), `zc-presentation` (the pages), `zc-world`
  (the shared `Where` world-selector group a storefront or board may carry).
- **Depended on by**: nothing. It sits BESIDE `zc-objectives` as a second top-layer consumer
  module, which is what keeps the graph a DAG.
- **Reverse-edge trap**: `zc-loot`, `zc-progression`, `zc-presentation` and `zc-world` may NEVER
  import commerce. A quest that wants a price, a reward kind that wants a balance, a shared page
  primitive that wants a storefront are all SEAMS commerce fills from above or the wiring root
  registers, never an import from below. **Commerce may never import `zc-dialogue` either**: a
  conversation opens a shop through the Destination registry in `zc-presentation`, which is exactly
  what that registry exists for.
- **The future edge that is legal**: `zc-instance -> zc-commerce`, the day a paid queue entry lands.
  Commerce never imports instance, so it is a one-way edge with no cycle. Record it in both routers
  when it happens rather than pre-paying for it now.

## The engine / authoring split, and why it is absolute

Every package here has two halves and they never mix:

- The **ENGINE** half (`currency/`, `cost/`, `rotation/`, `shop/`, `board/`) holds RUNTIME values
  and the engines over them. Nothing here carries a codec, reads an asset store, or names a file.
  That is what lets the whole economy be exercised by handing it numbers - no server, no store, no
  booted engine - which is exactly what its tests do.
- The **AUTHORING** half (`commerce/asset/`, `currency/asset/`, `shop/asset/`, `board/asset/`)
  holds the Pattern A codecs, the stores, the folds and the validators. It is the ONE authored
  surface, and it deliberately imports nothing from the engine.

So a price has exactly two forms: `commerce.asset.CostAsset` is what an author writes, `cost.Cost`
is what the engine charges, and one folds into the other. A third form - a codec on the runtime
value, a second parser beside the store - is the drift this split exists to prevent.

**[`commerce/fold/`](src/main/java/com/ziggfreed/common/commerce/fold/CLAUDE.md) is the seam
between them, and the ONE package allowed to see both.** It turns what an author wrote into what an
engine charges, draws and counts, and it is also where the module meets the rest of the server: the
`Currency` reward kind, the `Shop` / `Board` destinations, the owner-file readers and the defaults a
bare server runs on all live there, because a half that reached sideways for any of them would stop
being a half. Nothing in it decides anything; every decision already has an owner one package over.

## Packages

| Package | What it is |
|---|---|
| [`commerce/`](src/main/java/com/ziggfreed/common/commerce/CLAUDE.md) | `CommerceStore`, the ONE seam for everything commerce persists, plus the in-memory default and the holder a consumer replaces it through |
| [`currency/`](src/main/java/com/ziggfreed/common/currency/CLAUDE.md) | `CurrencyDef` + `CurrencyCatalog` + `CurrencyEngine`: the item-or-counter dispatch nothing above it ever branches on |
| [`cost/`](src/main/java/com/ziggfreed/common/cost/CLAUDE.md) | `Cost`/`ItemCost`/`CostScaling` + `CostEngine`: the one price vocabulary and the check/drain/refund authority, with receipts |
| [`rotation/`](src/main/java/com/ziggfreed/common/rotation/CLAUDE.md) | the deterministic rotating-pool primitive: cadence, seed, draw, selection strategies, reroll layering |
| [`shop/`](src/main/java/com/ziggfreed/common/shop/CLAUDE.md) | `ShopEngine`: the purchase pipeline, which is ordering and transactionality over other people's authorities |
| [`board/`](src/main/java/com/ziggfreed/common/board/CLAUDE.md) | `BoardEngine`: a board as a rotating VIEW over the quest pool, with the period lock, the accept site and the pre-charge reroll probe |
| [`commerce/fold/`](src/main/java/com/ziggfreed/common/commerce/fold/CLAUDE.md) | the JOIN: authored groups to runtime values, the three catalogs, the owner-file readers, the `Currency` reward kind, the `Shop`/`Board` destinations, and the defaults a bare server runs on |

## The rules that bind every engine here

- **Reward issuance is not ours.** A payout is ONE call to `RewardGrants.grantAll` over the shared
  kind table, and the room probe is ONE call to `LootRewardKinds.canAddAll`. There is no grant loop
  in this module, nothing switches on a reward kind, there is no second fit probe and no second
  queue. A capability the payout needs and zc-loot lacks is added THERE, for every domain at once.
- **Requirements are not ours either.** Every gate is a `progress.gate.GateSpec` answered by the
  shared `GateEvaluator`, the same machinery a quest accept uses, and a refusal is passed through in
  the evaluator's own words. No commerce-local requirements shape, walk or evaluator exists; a
  missing capability is added to `progress.gate` for every domain at once.
- **The engines hold no clock.** Every entry point takes `nowMs`, and ONE day number is derived from
  it and threaded through a whole purchase, so a transaction spanning midnight cannot check
  yesterday's count and record against today's.
- **A refusal is a TOKEN, never a sentence** (`"limit:daily"`, `"cost:currency:bounty_token"`,
  `"reroll:no_alternative"`). Turning one into words a player reads is the consumer's job, because
  only the consumer knows their language and its own wording.
- **Nothing is charged for something that could not have happened.** The reroll probe runs before
  the drain; the fit probe runs before the drain; a purchase that delivered nothing refunds its
  receipt and does not count. Each of those was a shipped bug somewhere before it was a rule.
- **Refund the RECEIPT, never the price.** An `Any` price charges one component, so refunding the
  price would hand back things nobody paid.

## Tests

`CurrencyEngineTest` (the backing dispatch, the caps, what an earn is and is not),
`CostEngineTest` (receipts, the never-half-charge ordering, scaling), `RotationEngineTest` (the
cadence, seed distinctness, draw reproducibility, reroll layering), `CommerceStoreTest` (limit and
reroll bookkeeping, the day rollover, the atomic cap), `ShopEngineTest` (the whole pipeline through
fake seams), `BoardEngineTest` (the rotating view, the period lock, the lapse re-arm, the accept
site, the reroll order). Every one of them runs with no server: pure cores plus fakes.

The JOIN has its own five: `CommerceFoldTest` (real authored files folded into engine values, each
compared against what the file says rather than against a number typed into the test),
`CommerceCatalogTest` (the three catalogs over seeded stores, including the generator family and the
live-versus-snapshot difference), `CommerceOwnerLayersTest` (leaf-by-leaf overrides, the re-read that
must not compound, the malformed file that costs the overrides and nothing else),
`CurrencyRewardKindTest` (the payout through the shared issuance pass, with every seam faked), and
`CommerceDestinationsTest` (both types readable now, declining until the pages land).

Tests assert mechanics, structure and invariants. Fixtures are author-owned; never assert numbers
that belong to somebody's balance pass.
