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
  - Each of the four also has a SECTION-SCOPED overload taking the sections to read (a trailing
    varargs of inventory component types), with `spendableSections()` as the ready-made
    backpack + storage + hotbar set. Use it wherever the answer decides what a player can be
    CHARGED: the combined view includes worn armor and utility slots, so a price read across it can
    both look affordable because of a helmet and then take that helmet. A `check` and its `take`
    must always name the SAME sections. First consumer: the economy's item-backed wallet.
- **World thread only** (touches the `Store`); every method is try-guarded to a no-op
  return so a missing component / invalid ref / engine throw never escapes into the
  caller. Backed by `CombinedItemContainer.addItemStack`/`removeItemStack`/
  `countItemStacks` + `ItemStackTransaction.getRemainder` (decompile-confirmed signatures).
- **[`InventoryGrant`](InventoryGrant.java)** (round-5, 2026-07-22) - the generic HOTBAR-FIRST-IF-
  SPACE-then-backpack-storage GRANT-ORDERING primitive: `grant(player, stack, fallback)` tries the
  hotbar first (only when the WHOLE stack fits), then backpack storage, then a caller-supplied
  `Consumer<ItemStack>` fallback (a world drop, a mail system, ... - policy this class does not
  own), returning a `Landed` (`HOTBAR`/`STORAGE`/`FALLBACK`). Distinct from `InventoryUtil` above
  (which counts/gives/takes a resource item BY ID across the combined view for a currency-shaped
  resource) - this is a single-stack, order-sensitive placement decision for a genuine item grant.
  **GRANT-side only, never consume-side**: a consumer's own consume/drain path should keep
  preferring backpack storage over the hotbar (mutating the hotbar container fans an Equipment
  update to every viewer, including the acting player, which has correlated with a client
  rendering issue in at least one consumer's own smoke testing when it fires mid-session under a
  locked/mounted camera) - do not widen this hotbar-first order to a consume/drain path.
  - **`canAdd(player, stack)` / `canAddAll(player, stacks)` are the PROBE half**: would `grant`
    land these, without putting anything anywhere. This is the ONE fit check every consumer asks
    before charging a price, spending a completion, or announcing a reward - the MMO's quest /
    shop / command-reward space checks and RpgStations' conversion-output and stamp-return checks
    all read it, so "is there room" means one thing across the whole family. Fails closed (no
    inventory, invalid ref, engine throw -> false). A batch is checked against STORAGE alone while
    a lone stack also gets the hotbar: the hotbar is a per-stack placement decision `grant` makes
    one stack at a time, so no container can answer for a whole batch, and storage is where every
    stack ends up anyway - the batch answer can only under-promise, which is the safe direction.
  - **Pair a probe with its own granter.** `InventoryGrant.canAdd` mirrors `InventoryGrant.grant`
    (hotbar + storage); `InventoryUtil.canFit` mirrors `InventoryUtil.give` (the combined view,
    every section). They deliberately answer about different space, so do NOT merge them or read
    one before calling the other - that is how a "checked" grant still lands somewhere nobody
    expected, or fails after the price was taken.

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
- **[`InventorySections`](InventorySections.java)** - package-private: the canonical six-section-id
  list (Armor / Hotbar / Storage / Utility / Tool / Backpack, fixed iteration order) shared by
  `InventorySnapshot` (capture/restore order) + `InventoryStripPolicy` (the default strip set), so the
  two cannot drift if the engine ever adds or renames a section. One vocabulary, one place.
- Test: **[`InventoryStripPolicyTest`](../../../../../../test/java/com/ziggfreed/common/inventory/InventoryStripPolicyTest.java)**
  covers the strip-policy dials (section keep/strip + whitelist/blacklist item rules) with no engine bootstrap.
- **[`InventorySnapshotStore`](InventorySnapshotStore.java)** - durable, crash-safe per-player store
  (the twin of `instance.reward.PendingRewardStore`): file-backed JSON, atomic write, serialized
  `flush`, each `ItemStack` persisted via its own engine `CODEC` (-> BSON -> JSON). `captureAndStrip`
  persists BEFORE touching the live inventory (crash-safety invariant); `restoreAndClear` applies then
  drops the snapshot only on success (a throw leaves it for the next-login retry). The consumer wires
  the lifecycle (entry strip, exit + next-login restore) - Kweebec's `RoundInventoryGuard` is the model.
