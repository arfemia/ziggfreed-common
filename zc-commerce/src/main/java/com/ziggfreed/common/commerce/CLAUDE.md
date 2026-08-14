# commerce/ - the persisted-state seam (module `zc-commerce`)

Router for `com.ziggfreed.common.commerce`. Everything the commerce engines remember about one
subject, behind one interface.

| Class | What it is |
|---|---|
| [`CommerceStore`](CommerceStore.java) | the seam: a counter-backed wallet, per-offer purchase counts, per-position reroll state, plus two capability probes |
| [`InMemoryCommerceStore`](InMemoryCommerceStore.java) | the ready-made one every test drives the engines with, and the honest degrade before a persistent one is installed |
| [`CommerceStores`](CommerceStores.java) | which store this server uses; a consumer installs its own at setup and the previous one stops being asked |

## Rules to keep

- **ONE seam, not three.** A wallet in one store and its spend record in another is the split that
  lets the two disagree. A consumer swapping in its own persistence swaps all of it at once.
- **Producer REPLACEMENT, never layering.** `CommerceStores.install` replaces; there is no stack and
  no fallback chain, so two stores holding two versions of one wallet cannot exist. Read
  `CommerceStores.get()` at CALL time rather than caching it in a field, because a consumer's setup
  may run after the engine that reads it was built.
- **An item-backed currency never reaches here.** Its balance IS the inventory count, so the
  currency engine reads and writes it through the inventory and this store is never asked. Only
  counter balances live in `balance`.
- **A store says what it can hold.** `recordsPurchases()` and `recordsRerolls()` are honest
  capability probes, the same shape the quest progress store uses: a store that cannot count says
  so, and the engine reports the authored limits as inert rather than letting them quietly not work.
- **Neutral answers for a subject nobody has recorded anything about.** Every read answers zero or
  empty rather than throwing, because a first-time buyer is the common case, not an error.
- **A rollover needs no sweep.** Reroll state is keyed by `(pool, period)` and a purchase count
  carries the day it belongs to, so a new period and a new day simply stop matching. Never add a
  cleanup pass over either.
- **`commitReroll` is cap-checked ATOMICALLY.** It answers false without mutating anything, which is
  what lets a caller charge around it and compensate on a false rather than discovering the refusal
  afterwards.

## Who installs the store

The wiring root installs the in-memory one as the DECLARED default at setup, through
[`commerce/fold/CommerceDefaults`](fold/CLAUDE.md). It is said out loud rather than left implicit,
because a server whose purchases stopped surviving a restart should be able to find out why from its
boot log - and it is the one line a persistent store lands in later. Common's own setup runs before
any consumer's (every consumer declares this library as a dependency), so a consumer installing its
own store in its own setup is never clobbered by it.

## Still to fill

The persistent zc DEFAULT - a component-backed store registered through the shared runtime's
registration surface, the shape `objectives.runtime.ProgressionDefaults` uses for progression - is
not built yet, so a bare server's shops work and forget at restart. A consumer that keeps this state
itself (its own component, its own database) installs its own implementation and needs none of it.
