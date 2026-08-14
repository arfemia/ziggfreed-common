# commerce/ - the persisted-state seam (module `zc-commerce`)

Router for `com.ziggfreed.common.commerce`. Everything the commerce engines remember about one
subject, behind one interface.

| Class | What it is |
|---|---|
| [`CommerceStore`](CommerceStore.java) | the seam: a counter-backed wallet, per-offer purchase counts, per-position reroll state, the write-it-in surface, plus two capability probes |
| [`RerollState`](RerollState.java) | one pool's reroll state for one period as a whole, for the two callers that need all of it at once |
| [`CommerceComponent`](CommerceComponent.java) | the persisted per-player state and the state machine over it: nine packed string leaves, saved with the world |
| [`ComponentCommerceStore`](ComponentCommerceStore.java) | the DEFAULT store: resolve the subject's component and delegate |
| [`InMemoryCommerceStore`](InMemoryCommerceStore.java) | the ready-made one every test drives the engines with, and the honest degrade a consumer can install deliberately |
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
- **The write-it-in surface is ABSOLUTE, every method of it.** `setBalance`, `setLifetimeSpent`,
  `setPurchases` and `setRerolls` write what they are given rather than adding to it, so a pass that
  runs twice leaves the same state. That is what makes a one-time import safe to get wrong. Building
  one out of `recordSpend` and `recordPurchase` instead would double a tally the second time.
- **A migration is CLAIMED, never checked-then-run.** `claimMigration` records the mark and answers
  true in the same breath, so two paths reaching it cannot both decide to go. A store that cannot
  keep the mark answers FALSE and the migration does not run, because a migration that re-runs every
  login is worse than one that never ran.

## The persisted state

`CommerceComponent` is where a player's economy lives, and `ComponentCommerceStore` is a lookup and a
call. The split is the same one the progression store makes and for the same reason: the state
machine stays exercisable with no server, which is where every rule above is actually pinned.

- **Nine packed string leaves, not nine map codecs.** Each map travels through
  [`CommerceBlob`](CommerceBlob.java) as `key=value|key=value`, with anything that could carry either
  reserved character base64-encoded - and a SET encodes each id separately, because encoding the
  joined text protects the outer frame while leaving an id carrying the inner separator to split
  itself in two. That wire form is a CONTRACT: it is what every saved world holds.
- **A new leaf is APPENDED, never inserted.** A blob saved before a leaf existed has no value for it
  and decodes to empty, which reads as "this player has none of that" everywhere.
- **A pool holds ONE period.** The period is a field rather than part of a key, so a rollover drops
  the old record on the first write of the new one and a stale period simply stops matching. Never
  add a sweep.
- **A read never creates.** No handle, no live entity or no component reads neutral and drops writes
  with one fine line. The single create path is the connect hook, the one moment a `Holder` is in
  hand. Which also settles the offline question: commerce state is per-entity, so an edit for
  somebody who is not standing in a world is refused rather than written where nothing reads it.
- **`CommerceBlob` is deliberately not shared with the progression component's identical packing.**
  That one lives in `zc-objectives`, a module this one may not depend on - both sit at the top of the
  graph as peers. The day a third module wants it, the lift target is `zc-core`, never a third copy.

## Who installs the store

The wiring root registers the component TYPE and calls
[`commerce/fold/CommerceDefaults`](fold/CLAUDE.md), which installs the component-backed store as the
DECLARED default and hooks the connect event that attaches one. The type registration is
unconditional and cannot wait: a component type registered after a world has loaded cannot be read
off entities saved carrying it. Attaching one is conditional, so a consumer that installs its own
store never gets a component nothing reads stamped onto every player.

Common's own setup runs before any consumer's (every consumer declares this library as a dependency),
so a consumer installing its own store in its own setup is never clobbered.
