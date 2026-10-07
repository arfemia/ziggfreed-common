# stats/ - item-carried stats as native modifiers

`com.ziggfreed.common.stats` is a split package: `StackStats`, a pure item-metadata record, lives in zc-core so any module can stamp a stack; the ECS bridge (`EquipStatBridge`, `StatMirror`, `StatChannelAudit`, `StatIndexCache`) and `gearset/` live here.

- `stats/` and zc-core's `counter/` never merge: an item-carried stat lives here, a per-subject tally in `counter/`, and a tally that must reach a stat channel is mirrored onto it with `StatMirror`.
- `StackStats.Entries` stores percent channels in whole percent points (`10.0` is +10%) and flat channels raw; which family a channel belongs to is its owner's business. `merge` is the one summing authority, and `stampReplacingWithCount` writes the count it is given (it never increments).
- The engine (`StatModifiersManager`) never applies a held item's own `Utility.StatModifiers`, so the bridge does. It does apply the utility-slot (offhand) item's, so the bridge applies only that item's `StackStats`, or the bonus doubles.
- The library installs the ONE bridge (`EntityBootstrap.installEquipStatBridge`, namespace `ziggfreedcommon`) and its three trigger subclasses. ECS systems are class-keyed, so never install a second bridge or register your own triggers: read it through `EntityBootstrap.equipStatBridge()` and hang derived work on `addAppliedListener`, which runs after every recompute (keep it cheap and idempotent).
- `EquipStatBridge.equippedSnapshot(store, ref)` answers what the entity has on by item id (`EquippedSnapshot`), through the same container reads the stat walkers use, so a listener never re-derives the slot layout.
- `planUtility` and its `UtilityPlan` / `UtilityPut` / `StatKey` types are public and permanent (2.2.0): the bridge and a gear-set tier share one diff-skip and stale-key core so they agree on what "stale" means.
- A consumer calls `recomputeAll(store, ref)` once, at `PlayerReadyEvent`.
- The triggers ride only non-deprecated inventory events, never the `Legacy*ChangeStatSystem` family.
- Touch an `EntityStatMap` only through keyed `putModifier`/`removeModifier`, never `setStatValue` or `addStatValue`.
- Resolve a stat index through `StatIndexCache`, not `util.AssetIndexCache`: a custom channel can legitimately sit at index 0, which `AssetIndexCache` answers as unresolved.
- Run `StatChannelAudit` once, late (the first `PlayerReadyEvent`), after dynamically registered channels exist.

## gearset/

A set is content (`Server/ZiggfreedCommon/GearSets/<Set_Id>.json`; the library ships none), applied by a single `AppliedListener` on the installed bridge (`EntityBootstrap.installGearSets`), never a fourth trigger system.

- A tier's condition is a conjunction of independent minimums (`Pieces`, `Armor`, `Held`, `Utility`), never a bare count. `Held` counts only when the active-hand item is a member that is not also worn; author `Held` or `Utility` as `1` or leave it out (`0` is `BAD_PIECE_COUNT`). Every tier whose condition holds applies, so tiers stack.
- A tier's `Effect` id must be dedicated to the set: the engine takes it off whenever no active tier wants it, whoever put it on.
- A tier's `StatModifiers` is the native item block byte for byte, decoded through `StatModifierSpec`, not the engine's `StaticModifier.CODEC`: `CalculationType` and `Target` are closed words matched without regard to case, an unknown word fails the read, and unauthored reads `Additive` / `Max` / `0`. Under `Parent`, `Bonuses` replaces wholesale and every other leaf merges.
- Each recompute settles the look by `has` asked of the entity, never of the remembered row: it takes off every effect the engine answers for that no active tier wants, puts on every wanted one the entity lacks, and puts none on while the player is dead (`DeathComponent`). The transient `GearSetApplied` row is forgotten whenever the entity leaves its store, whatever the `RemoveReason`.
- Every modifier key starts with `zigset:` (`GearSetKeys`, a tier addressed by its position), and the engine never probes or removes any other key.
- The first recompute in a store is a hydrate, not a flip: it sweeps every `zigset:` key on the stat map, takes off every look a folded set or the player's saved `GearSetLooksComponent` names, and announces nothing. That component is registered unconditionally at setup, and a recompute only replaces it in place, never adds one.
- `GearSetEffects` is a seam the wiring root fills with `NativeEffectUtil`'s apply, remove and has; unfilled, it reports once and only the set's look is missing.
- An item names its set in its own `items.<Id>.description`. Never write per-instance `ItemDisplayMetadata` for it: the stack stops stacking, re-enters the bridge's content trigger and misleads the next holder.
- A set's top-level `Requires` (zc-core `PresenceRequiresCodec`) is read only for mod presence: a set gated on a companion mod is dropped at load where that mod is missing, so its members never index and its notice never fires.
