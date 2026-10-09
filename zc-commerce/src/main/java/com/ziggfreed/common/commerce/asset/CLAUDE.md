# commerce/asset/

- Add a group here only when two or more commerce types need it; a group one type wants lives in that type's own codec.
- Every price is a `CostAsset`; never add a terse `{currency, amount}` shorthand beside it.
- A group exposes the accessors an author's answer needs and nothing more: `commerce/fold/CommerceFold` turns it into an engine value and decides what an unauthored or unreadable group degrades to.
- A domain's slot codec starts from `SlotAsset.appendLeaves` and adds only its own filter word (a shelf's `Tier`, a board's `Difficulty`).
- `HideAxis` is the one presence read every commerce type takes off its `Requires` and its `Season`: `present(enabled, season, requires)` (through zc-progression's `FeatureLift.present`) and `lock(requires)`, both live, and `hides(requires)` names the lifted conditions for an audit. A plain top-level feature or `hytale:mod_installed` condition, or a `Season` out of its calendar event, decides whether the thing exists; never lift a second way, never evaluate a lifted condition as a lock, and never let a season reach `lock`.
- `WhereAxis` is the world read a storefront and a board take off their `Where`: `present(where, viewer)` asks zc-world's `WorldSelector.match` (never a second matcher), and an unauthored or empty `Where` is every world. The viewer is a `WhereValidator.LoadedWorld` read by `viewer(World)` or `viewer(Store)`; an unreadable world matches no `Where`, so that player sees only what exists everywhere.
