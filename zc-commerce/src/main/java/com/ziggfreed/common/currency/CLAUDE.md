# currency/ - balances, and the one dispatch under them (module `zc-commerce`)

Router for `com.ziggfreed.common.currency`. What a currency IS, and the single place the
item-or-counter question is asked.

| Class | What it is |
|---|---|
| [`CurrencyDef`](CurrencyDef.java) | one currency as the engine sees it: what backs it, its cap, its death loss and decay, and a namespaced `meta` bag of consumer knobs |
| [`CurrencyCatalog`](CurrencyCatalog.java) | which currencies exist; the seam the authoring layer fills, ids matched case-insensitively |
| [`CurrencyEngine`](CurrencyEngine.java) | `balance` / `credit` / `debit` / `refund` / `set`, plus the death-loss and decay passes |
| [`ItemWallet`](ItemWallet.java) | how an item-backed balance is read and moved; a seam, so every pure part of the engine is exercisable with no inventory |
| [`NativeItemWallet`](NativeItemWallet.java) | the real one, over `inventory/InventoryUtil`, resolving the player off the subject's own handle; reads and takes are scoped to backpack + storage + hotbar |
| [`CurrencyObserver`](CurrencyObserver.java) | who hears about an earn and a spend; guarded, so a throwing listener never reaches the transaction |

## Rules to keep

- **No caller ever branches on backing.** An item-backed currency's balance IS the inventory count;
  a counter-backed one's lives in the commerce store. Every read and write dispatches on that one
  question inside the engine, and nothing above it may ask.
- **An item-backed balance is what a player CARRIES.** `NativeItemWallet` counts and takes across
  backpack + storage + hotbar only; armor and utility slots are out of scope, so an offer can never
  read as affordable off the strength of a helmet and then take the helmet to pay for itself. A
  `give` still lands across the whole inventory: where a granted item comes to rest is a question
  about space, not about what may be spent.
- **An unknown currency is INERT, not an error.** It reads zero, credits nothing, debits nothing,
  with one line saying so. Content naming a currency whose pack is not installed stays dormant
  rather than inventing a balance - the library's standing unknown-id rule.
- **A REFUND is not an earn.** `refund` puts a balance back and announces nothing, and it undoes its
  own share of the lifetime spend. A consumer counting earnings must never count a purchase that
  failed as income. `credit` is for a genuine payout; nothing else may use it to compensate.
- **A debit is all or nothing**, and it records the spend and announces it only when it succeeded.
- **An earn announces the delta that LANDED**, never what was asked for, so a capped credit reports
  the truth.
- **The engine holds no clock.** `applyDecay` takes the elapsed days; how long somebody was away is
  a question about a session, not about a wallet.
- **Experience conversion is deliberately absent.** What a mod's experience is worth is that mod's
  vocabulary: it reads its own knob off `CurrencyDef.meta` and calls `credit`. The same goes for
  sidebar placement and anything else a consumer wants to know - `meta` is the namespaced carrier,
  and the engine reads none of it.
- **A name is a KEY or nothing.** `CurrencyDef` holds a `nameKey`, never display text: an
  item-backed currency with none reads its name off the backing item's own native key, and what a
  player sees is resolved by their own client.
- **Where the definitions come from is not this package's business.** `CurrencyCatalog` is a seam,
  and [`commerce/fold/`](../commerce/fold/CLAUDE.md) is what fills it off the authored wallets -
  along with the `Currency` reward kind, which pays through this engine but resolves the wallet out
  of that catalogue and therefore cannot live here.
