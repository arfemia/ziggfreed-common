# commerce/

- `CommerceStore` is the one seam for wallets, purchase counts and reroll state. A consumer replaces it whole through `CommerceStores.install` (no layering), and callers read `CommerceStores.get()` at call time, never from a field.
- Item-backed currencies never reach the store: only counter balances live in `balance`.
- Build an import from the `set*` methods, never `recordSpend` or `recordPurchase`, which double a tally on a second run. A store that cannot keep a migration mark answers `claimMigration` false.
- `commitReroll` checks the cap atomically and answers false without mutating, so charge first and compensate on false.
- `CommerceComponent`'s nine packed leaves (the `CommerceBlob` format) are what saved worlds hold: append a leaf, never insert or rename one.
- A read never creates the component; only the connect hook attaches it, and an edit for a player who is not in a world is refused.
- Rollovers need no sweep: reroll state is keyed by (pool, period) and a purchase count carries its day.
