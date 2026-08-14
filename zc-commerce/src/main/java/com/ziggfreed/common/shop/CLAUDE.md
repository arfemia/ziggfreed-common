# shop/ - buying something (module `zc-commerce`)

Router for `com.ziggfreed.common.shop`. The purchase pipeline: what refuses it, the one order it
happens in, and what it undoes.

| Class | What it is |
|---|---|
| [`ShopEngine`](ShopEngine.java) | `canPurchase` / `purchase` / `priceFor` / `checkLimits`, plus the refusal tokens and `epochDay` |
| [`ShopOffer`](ShopOffer.java) | what the engine needs to know about one thing for sale; an interface with defaults, implemented by whatever authored it |
| [`ShopCatalog`](ShopCatalog.java) | which offers exist, and which rotating pool each belongs to |
| [`PurchaseLimits`](PurchaseLimits.java) | how often one buyer may take the same offer: a daily limit and a lifetime one, independently optional |

## The pipeline, and why every step belongs to somebody else

`purchase` is ORDERING and transactionality. Each step is another authority's answer, asked in the
one sequence that cannot leave a buyer short:

1. **gate** - enabled, then the shared `GateEvaluator` over the offer's `Requires`. The refusal is
   passed through in the evaluator's own words, so a shop lock and a quest lock read the same.
2. **limits** - the commerce store's counts, against ONE day number derived from the injected clock.
3. **afford** - `CostEngine.check`, naming the first shortfall.
4. **room** - ONE `LootRewardKinds.canAddAll` over the whole reward list.
5. **drain** - `CostEngine.drain`, which answers a receipt of what it took.
6. **grant** - ONE `RewardGrants.grantAll` call over the shared kind table.
7. **refund** - if NOTHING was deliverable, the receipt goes back and the purchase does not count.
8. **record** - against the same day number step two read.

## Rules to keep

- **No grant loop, no kind switch, no second fit probe, no second queue.** Issuance is zc-loot's;
  this engine calls it once. A capability the payout needs and zc-loot lacks is added THERE, so
  every payout site in the library gains it at once.
- **The room probe asks about the WHOLE list at once.** Asking per reward asks each of them about
  the same last free slot, so three rewards each answer yes and the third still lands on the floor -
  after the price was charged, which is the moment the probe exists to prevent.
- **ONE day number, threaded end to end.** A purchase spanning midnight must not check yesterday's
  count and record against today's, which is why the clock is injected and read exactly once.
- **A queued reward counts as DELIVERED.** Only a payout where nothing reached the buyer and nothing
  is waiting for them refunds; a reward queued for next connect is value they are going to get.
- **An offer paying out nothing still completes.** Nothing is a real answer, not a failure, so the
  refund rule applies only to a payout that was supposed to hand something over.
- **A refusal is a TOKEN.** `"limit:daily"`, `"cost:currency:<id>"`, `"no_room"`. Turning one into
  words a player reads is the consumer's job.
- **Scaling is applied HERE, once.** `priceFor` is the only place an offer's curve meets the buyer's
  own purchase count, so a chip somebody reads and the amount they are charged cannot differ.
- **`ShopOffer` is an interface with defaults on purpose.** An authored offer carries far more than
  a purchase needs, and mapping it into a second object every time somebody opens a shop is a copy
  waiting to fall out of step. A new question here is a new default method, never a new required one.
- **What implements it is a VIEW, and it lives one package over.** The authoring layer may not
  import an engine type, so an authored offer cannot answer this interface itself;
  [`commerce/fold/ShopEntryOffer`](../commerce/fold/CLAUDE.md) does, holding the asset it came from
  rather than copying it and being rebuilt whenever the catalogue is. `ShopCatalog` is filled from
  the same place.
