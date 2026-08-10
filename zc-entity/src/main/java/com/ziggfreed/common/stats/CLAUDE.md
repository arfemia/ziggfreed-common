# stats/ - unified per-stack + tag entity-stats bridge (RPG Stations extraction, scope 2 wave 1)

Router for `stats/`. The mod-root `CLAUDE.md` PARADIGM applies: item-carried stats (per-stack
enhancement, native tool `Utility.StatModifiers`) are a generic, mod-agnostic Hytale primitive - a
consumer converts them into native `EntityStatMap` modifiers so the native map stays the ONE
aggregation authority, never a per-mod at-use metadata fold. Self-contained: no dependency on another `common/` domain
beyond the Hytale server jar itself (see [`StatIndexCache`](StatIndexCache.java)'s javadoc for why
it does NOT route through `util.AssetIndexCache`). All world-thread, try-guarded, static /
config-free; `StackStats` and every pure decision core are unit-testable without a live server.

- **[`StackStats`](StackStats.java)** - the ONE generic per-stack stat/enhancement record.
  Metadata blob key `"ZigStackStats"`. Codec fields `Entries` (`Map<String, Double>`: percent
  channels in WHOLE PERCENT POINTS, flat channels raw - the one numeric convention every
  reader/writer in this domain shares) and `StampCount` (`Integer`, enhancement-stamp counter).
  API mirrors the MMO's proven `item.ItemStatsMeta` shape: `read`/`entriesOf`/`stampCountOf`
  (graceful no-throw, `null`/0 default), `merge`/`mergeWith` (same-stat SUMMATION - the ONE
  summing authority), `stampReplacing` (wholesale replace, `StampCount` untouched),
  `stampReplacingWithCount` (explicit `StampCount` write - the caller passes
  `stampCountOf(stack) + 1`, this method does not increment itself). Any writer (an MMO-side
  stamper, a standalone RPG-Stations stamper, a future enchanting station) writes THIS record, so
  cross-mod cap/budget accounting stays consistent no matter which system stamped first.
- **Native tool stats** (decisions 44/45, the `Zig_Entity_Stats`/`HeldItemStatsTag` tag is DELETED):
  a tool carries stats in its item asset's native `Utility.StatModifiers` block (leaving `Usable`
  and `Compatible` unset, both default-false, keeps the field side-effect-free - proven at
  `StatModifiersManager:98-107` and the offhand-utility capability doc). The engine never applies a
  HELD item's own Utility stats, so `EquipStatBridge` applies them; there is no mod-owned tag
  vocabulary any more (full fidelity comes free - the field is codec-validated `StaticModifier[]`).
- **[`EquipStatBridge`](EquipStatBridge.java)** - the equip-watcher engine: converts a player's
  item-carried stats into keyed native `EntityStatMap` modifiers via `putModifier`/`removeModifier`
  across FOUR sources (the double-apply partition is the load-bearing invariant, documented on the
  class):
  - **HELD item**: its per-stack `StackStats.Entries` (keys `held:0`) PLUS its item asset's native
    `Utility.StatModifiers` (keys `util:<offset>`) - the util block is applied here because the
    engine never applies a held item's own Utility stats.
  - **ARMOR**: per-stack `StackStats` only (keys `armor:<i>`) - armor asset stats are the engine's
    native `ItemArmor.StatModifiers`.
  - **UTILITY-SLOT ACTIVE item (offhand)**: per-stack `StackStats` ONLY (keys `offhand:0`) - NEVER
    its asset `Utility.StatModifiers`, which the engine DOES apply natively (Compatible-gated);
    applying them again would double-count.

  `install(namespace[, entryFilter])` returns a bound instance; the consumer's contract is (a)
  register ONE of its own concrete subclasses of the THREE ABSTRACT trigger bases (`new
  EquipStatBridge.ActiveSlotTrigger(bridge) {}` / `new EquipStatBridge.ContentChangeTrigger(bridge)
  {}` / `new EquipStatBridge.UtilityContentChangeTrigger(bridge) {}`) via its own
  `getEntityStoreRegistry().registerSystem(...)` - **the ECS system registry is CLASS-KEYED (a
  second `registerSystem` with the same Class collides), so this package ships only the abstract
  bases, exactly like `cast.AbstractWorldFrameSystem`; never make these concrete or two
  consumers/namespaces sharing one class will collide** - and (b) call `recomputeAll(store, ref)`
  once at `PlayerReadyEvent` (inventory components are ensured/hydrated strictly before that event,
  E6-proven, so a full recompute there is the safe hydrate authority).
  - **Triggers** (E6-proven, non-deprecated ONLY): `ActiveSlotTrigger` mirrors
    `InventorySystems.ActiveSlotChangedEntityEventSystem` (fires on `InventorySetActiveSlotEvent`
    for ANY section - hotbar OR the utility section id `-5` - and recomputes every source, so a
    utility active-slot switch is already covered); `ContentChangeTrigger` mirrors the
    per-tick-drained `InventoryChangeEvent` filtered to the Hotbar component with the ACTIVE slot
    modified (`Transaction.wasSlotModified(activeSlot)`) or the Armor component; the new
    `UtilityContentChangeTrigger` is the same twin filtered to the `InventoryComponent.Utility`
    component (offhand content change). NEVER the deprecated
    `LegacyHotbarChangeStatSystem`/`LegacyUtilityChangeStatSystem` (read only as precedent for the
    filter shape, never called/extended).
  - **Key scheme**: `"<namespace>:held:0"` + `"<namespace>:util:<offset>"` (a player has one
    active hand; util offset per modifier within a stat index) / `"<namespace>:offhand:0"` /
    `"<namespace>:armor:<i>"` per armor-container slot.
  - **Apply = diff-skip + stale-key sweep**, adapted from `StatModifiersManager`'s
    `addItemStatModifiers`/`clearAllStatModifiers` discipline. The `StackStats` sources
    (`held`/`armor`/`offhand`) each resolve to at most one additive `MAX` modifier per stat, so
    `EquipStatBridge.plan(...)` uses a per-SLOT key (no numbered-offset walk). The held-item
    `util` source is a native `Int2ObjectMap<StaticModifier[]>` of PRE-RESOLVED indices with full
    fidelity (Target MIN/MAX + additive/multiplicative pass straight through - `putModifier` takes
    the `Modifier` directly), so `EquipStatBridge.planUtility(...)` mirrors the engine's own
    per-array-position `keyPrefix + offset` keying + higher-offset + absent-index sweeps. Both
    `plan` and `planUtility` are PURE decision cores (package-private, unit-tested against fake
    seams - lambdas / a hand-built `Int2ObjectMap`, no live `EntityStatMap` needed).
  - **`bridgedSum(store, ref, statId)`** (gate decision 35): the bridge's OWN current contribution
    to `statId`, summed fresh across ALL bridge namespaces - held `StackStats` + the held item's
    `Utility.StatModifiers` ADDITIVE contributions + offhand `StackStats` + every armor slot (not
    read back from the `EntityStatMap`, so it is accurate even before the first apply). The seam a
    consumer's DOT branch subtracts so per-stack enhancement (and a held tool's util stats) never
    buff a DOT tick, matching the pre-migration behavior where the DOT path never read held
    metadata at all. Only ADDITIVE util modifiers are summed (a multiplicative modifier has no
    linear scalar a DOT subtraction could use; the MMO's DOT-relevant channels are all additive).
  - Unknown stat id (channel not registered): skip + one-time warn, never a throw.
- **[`StatMirror`](StatMirror.java)** - idempotent keyed put/remove of a SINGLE native additive
  `MAX` `StaticModifier`: `set(store, ref, statId, key, value)` writes-or-replaces (skips the
  actual engine write when an equal modifier is already present under `key` - safe to call
  unconditionally on a hot path), `remove(...)`. The generic primitive for mirroring a derived
  scalar (a skill level, a purchased multiplier) onto a native channel so a `resolve*`-style
  reader needs zero at-use lookups. `decideOrSkip` is the pure idempotence core (package-private,
  directly unit-testable - `StaticModifier` is a plain constructible POJO, no fake seam needed).
- **[`StatChannelAudit`](StatChannelAudit.java)** - boot-time channel-presence check (the
  load-order silent-drop guard, risk R2): `audit(expectedChannelIds)` verifies each id resolves in
  the `EntityStatType` asset map and logs one `SEVERE` line per miss naming the
  register-before-items explanation. Call it once, late (first `PlayerReadyEvent` is the intended
  site) after every jar-bundled + dynamically-registered channel has had its chance to register.
  Does NOT detect an item whose authored modifier already silently dropped (out of scope) - only
  that the CHANNEL itself is missing.
- **[`StatIndexCache`](StatIndexCache.java)** - package-private, shared by all four classes above:
  memoizes an `EntityStatType` id -> asset-map index (mirrors `util.DamageCauseCache`'s technique
  for a different asset type; deliberately NOT `util.AssetIndexCache`, whose "cache only `idx > 0`"
  rule would wrongly treat a legitimately-index-0 custom stat channel as unresolved forever - see
  its javadoc).

## Conventions specific to this package

- **Numeric convention**: `StackStats.Entries` follows ONE rule -
  percent-family channels store WHOLE PERCENT POINTS (`10.0` = +10%), flat channels store their
  raw number. This package does not know which channel is which family; that classification lives
  with whoever owns the channel id (a consumer's own docs/constants).
- **Never write to the `EntityStatMap` outside a keyed `putModifier`/`removeModifier` call** - no
  class here ever calls `setStatValue`/`addStatValue` (that would mutate the CURRENT value, not a
  modifier bound, and would not diff/sweep cleanly on the next recompute).
- **A consumer never subclasses `StackStats`/`StatMirror`/`StatChannelAudit`** -
  those are plain static/data classes. Only `EquipStatBridge`'s three trigger bases are meant to be
  subclassed, and only for the class-keyed-registry reason above.
