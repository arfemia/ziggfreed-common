# inventory/ - combined-inventory item helpers

Router for `inventory/`. The mod-root `CLAUDE.md` PARADIGM applies: this is a generic,
mod-agnostic Hytale primitive (any minigame granting/spending a custom resource item
needs it), so it lives here, not duplicated in a consumer.

- **[`InventoryUtil`](InventoryUtil.java)** - static helpers keyed by item id, operating
  across ALL inventory sections at once via `InventoryComponent.getCombined(store, ref,
  InventoryComponent.EVERYTHING)`:
  - `count(store, ref, itemId)` -> summed quantity across sections.
  - `has(store, ref, itemId, n)` -> at least `n` held.
  - `give(store, ref, itemId, n)` -> add `n`, returns how many did NOT fit.
  - `take(store, ref, itemId, n)` -> remove up to `n`, returns how many were removed.
  - `spend(store, ref, itemId, n)` -> all-or-nothing remove of exactly `n` (false if too few).
- **World thread only** (touches the `Store`); every method is try-guarded to a no-op
  return so a missing component / invalid ref / engine throw never escapes into the
  caller. Backed by `CombinedItemContainer.addItemStack`/`removeItemStack`/
  `countItemStacks` + `ItemStackTransaction.getRemainder` (decompile-confirmed signatures).
