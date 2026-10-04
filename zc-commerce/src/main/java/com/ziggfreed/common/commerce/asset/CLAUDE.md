# commerce/asset/

- Add a group here only when two or more commerce types need it; a group one type wants lives in that type's own codec.
- Every price is a `CostAsset`; never add a terse `{currency, amount}` shorthand beside it.
- A group exposes the accessors an author's answer needs and nothing more: `commerce/fold/CommerceFold` turns it into an engine value and decides what an unauthored or unreadable group degrades to.
- A domain's slot codec starts from `SlotAsset.appendLeaves` and adds only its own filter word (a shelf's `Tier`, a board's `Difficulty`).
- `HideAxis` is the one presence read every commerce type takes off its `Requires`: `present(enabled, requires)` and `lock(requires)`, both live through zc-progression's `FeatureLift.liftKnown`. A plain top-level feature or `hytale:mod_installed` condition decides whether the thing exists; never lift a second way, and never evaluate a lifted condition as a lock.
