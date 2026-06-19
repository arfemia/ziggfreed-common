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

**Full-inventory preserve/restore (a minigame's "keep your overworld gear" lifecycle):**
- **[`InventorySnapshot`](InventorySnapshot.java)** - slot-exact capture/strip/apply across ALL
  six section components (Armor/Hotbar/Storage/Utility/Tool/Backpack via
  `InventoryComponent.getComponentTypeById`), preserving each `ItemStack`'s durability + metadata
  and each active-slot section's selected slot. Asymmetric by design: `capture` is ALWAYS the whole
  inventory; `strip(policy)` is the ONLY configurable step; `apply` restores the EXACT entry state
  (clears every section first - dropping in-round loot AND kept-on-entry items - then reapplies, so
  it is idempotent / retry-safe). World thread.
- **[`InventoryStripPolicy`](InventoryStripPolicy.java)** - governs ONLY what `strip` removes on
  entry, never the restore. Two dials: which **sections** are stripped (`keepSections(ARMOR_SECTION_ID)`
  leaves armor on) + an item **rule** (`whitelist`/`blacklist` of item ids). `STRIP_ALL` is the
  strip-everything default; `clearsSection` is the one-transaction fast path.
- **[`InventorySnapshotStore`](InventorySnapshotStore.java)** - durable, crash-safe per-player store
  (the twin of `instance.reward.PendingRewardStore`): file-backed JSON, atomic write, serialized
  `flush`, each `ItemStack` persisted via its own engine `CODEC` (-> BSON -> JSON). `captureAndStrip`
  persists BEFORE touching the live inventory (crash-safety invariant); `restoreAndClear` applies then
  drops the snapshot only on success (a throw leaves it for the next-login retry). The consumer wires
  the lifecycle (entry strip, exit + next-login restore) - Kweebec's `RoundInventoryGuard` is the model.
