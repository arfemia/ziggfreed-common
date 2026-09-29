# stats/ - unified per-stack + tag entity-stats bridge (RPG Stations extraction, scope 2 wave 1)

Router for `stats/`. The mod-root `CLAUDE.md` PARADIGM applies: item-carried stats (per-stack
enhancement, native tool `Utility.StatModifiers`) are a generic, mod-agnostic Hytale primitive - a
consumer converts them into native `EntityStatMap` modifiers so the native map stays the ONE
aggregation authority, never a per-mod at-use metadata fold. Self-contained: no dependency on another `common/` domain
beyond the Hytale server jar itself (see [`StatIndexCache`](StatIndexCache.java)'s javadoc for why
it does NOT route through `util.AssetIndexCache`). All world-thread, try-guarded, static /
config-free; `StackStats` and every pure decision core are unit-testable without a live server.

**Where the files live.** `com.ziggfreed.common.stats` is a SPLIT package: `StackStats` (a pure
item-metadata value record - codecs, an `ItemStack`, nothing else) sits in **zc-core** so any module
can read or stamp a stack without pulling in the ECS trigger machinery; everything on this page that
touches the entity store (`EquipStatBridge`, `StatMirror`, `StatChannelAudit`, `StatIndexCache`)
stays in **zc-entity**. One package, one router, two modules.

**`stats/` vs `counter/` - they never merge.** `stats/` is the native `EntityStatMap` bridging of
ITEM-CARRIED stats: a value lives on a stack or an item asset, and this package turns it into a
keyed native modifier on an entity. [`counter/`](../../../../../../../../zc-core/src/main/java/com/ziggfreed/common/counter/CLAUDE.md) (zc-core) is arbitrary named TALLIES keyed by a
`subject.Subject` id, with a persistence seam and no engine types at all. A "how many times has this
player done X" number is a counter and belongs there even when it later feeds a stat; a "what does
this sword add to Attack Damage" number is a stat and belongs here even when it happens to be an
integer someone increments. If a new type wants both, it wants a counter that a consumer mirrors
onto a channel through `StatMirror` - not a merged package.

- **[`StackStats`](../../../../../../../../zc-core/src/main/java/com/ziggfreed/common/stats/StackStats.java)** (in zc-core) - the ONE generic per-stack stat/enhancement record.
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

  `install(namespace[, entryFilter])` returns a bound instance, and the LIBRARY installs the one
  bridge: `EntityBootstrap.installEquipStatBridge` binds it under the `ziggfreedcommon` namespace and
  registers a concrete subclass of each of the THREE ABSTRACT trigger bases (`ActiveSlotTrigger` /
  `ContentChangeTrigger` / `UtilityContentChangeTrigger`) - **the ECS system registry is CLASS-KEYED
  (a second `registerSystem` with the same Class collides), so this package ships only the abstract
  bases, exactly like `cast.AbstractWorldFrameSystem`, and one installer means one set of modifier
  keys; a consumer never installs a second bridge and never registers its own copies of the three
  triggers, it reads the installed one back through `EntityBootstrap.equipStatBridge()`** - and the
  consumer's own contract is to call `recomputeAll(store, ref)` once at `PlayerReadyEvent`
  (inventory components are ensured/hydrated strictly before that event, E6-proven, so a full
  recompute there is the safe hydrate authority).
  - **Post-apply seam** - `addAppliedListener(AppliedListener)` fires `onApplied(store, ref)` after
    every `recomputeAll` pass, world-thread. A consumer keeping DERIVED state in step with these
    channels (the MMO's per-school resist effect sync) hangs it HERE instead of registering a
    fourth trigger subclass on the same three events: one seam covers every equip path the bridge
    already watches. Listeners must be cheap + idempotent (it fires on every slot switch); a
    throwing listener is isolated by the generic `forEachIsolated` helper so the rest still run.
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
    are PURE decision cores, unit-tested against fake seams (lambdas / a hand-built
    `Int2ObjectMap`, no live `EntityStatMap` needed); `plan` is package-private, `planUtility` is
    PUBLIC (below).
  - **`bridgedSum(store, ref, statId)`** (gate decision 35): the bridge's OWN current contribution
    to `statId`, summed fresh across ALL bridge namespaces - held `StackStats` + the held item's
    `Utility.StatModifiers` ADDITIVE contributions + offhand `StackStats` + every armor slot (not
    read back from the `EntityStatMap`, so it is accurate even before the first apply). The seam a
    consumer's DOT branch subtracts so per-stack enhancement (and a held tool's util stats) never
    buff a DOT tick, matching the pre-migration behavior where the DOT path never read held
    metadata at all. Only ADDITIVE util modifiers are summed (a multiplicative modifier has no
    linear scalar a DOT subtraction could use; the MMO's DOT-relevant channels are all additive).
  - Unknown stat id (channel not registered): skip + one-time warn, never a throw.
  - **`equippedSnapshot(store, ref)`** - what the entity has on by item id, as an
    [`EquippedSnapshot`](EquippedSnapshot.java) (`heldItemId`, `offhandItemId`, index-aligned
    `armorItemIds`, lower-cased `distinctItemIds`), read through the SAME container reads the stat
    walkers use, so a listener deciding by WHICH items are worn never re-derives the slot layout.
    A pure value with no engine type, so a decision core over it is tested on plain ids.
  - **`planUtility` and its `UtilityPlan` / `UtilityPut` / `StatKey` types are PUBLIC, a permanent
    2.2.0 contract** (javadoc'd to that grade on the class): the same per-array-position diff-skip
    and stale-key sweep is what a gear-set tier's block needs under its own prefix, and one core
    shared by both is what keeps the two from disagreeing about what "stale" means. Every address
    it probes or plans starts with the prefix; a null map is a pure sweep of every key under it.
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
- **[`StatIndexCache`](StatIndexCache.java)** - public (the `hytale:stat` factor provider reads it
  from `factor/`), shared by `EquipStatBridge`, `StatMirror`, `StatChannelAudit` and
  [`../factor/HytaleFactors`](../factor/HytaleFactors.java): memoizes an `EntityStatType` id ->
  asset-map index (mirrors `util.DamageCauseCache`'s technique
  for a different asset type; deliberately NOT `util.AssetIndexCache`, whose "cache only `idx > 0`"
  rule would wrongly treat a legitimately-index-0 custom stat channel as unresolved forever - see
  its javadoc).

## `gearset/` - asset-driven set bonuses over the bridge (2.2.0)

The GEAR-SET engine: a set is content, `Server/ZiggfreedCommon/GearSets/<Set_Id>.json`, and its
bonuses are applied by ONE `EquipStatBridge.AppliedListener` the library hangs on the installed
bridge (`EntityBootstrap.installGearSets`, right after the bridge), never a fourth trigger system.
No set content ships in this jar; a consumer or a pack authors the files.

- **[`GearSetAsset`](gearset/GearSetAsset.java)** (Pattern A, every top-level leaf
  `appendInherited`, the `OverheadIndicatorAsset` shape): `Text` (the shared `ContentTextAsset`
  group; `TitleKey` names the set), `Enabled` (default true), `Members` (item ids, matched without
  regard to case, a duplicate counting once, an item free to belong to several sets) and
  `Bonuses`, the tiers in order. **A tier's condition is a conjunction of independent minimums,
  never a bare count** (decision D9): `Pieces` (distinct members on anywhere; a held copy of a
  worn piece counts once), `Armor` (distinct members in armor slots), `Held` (the active-hand item
  is a member AND is not also worn in an armor slot, compared by id without regard to case, so a
  spare copy of a worn piece in hand never stands in for the weapon; ruling R13) and `Utility` (the
  utility-slot item is a member). `Held` and `Utility` are authored `1` to require them and left
  out otherwise; `0` is an error (`BAD_PIECE_COUNT`), never "must not be". Each minimum is
  nullable, and a tier authoring none is an error. Every
  tier whose condition holds applies, so tiers stack by construction and two may share a count. A
  tier carries its own `Text` (`TitleKey`, the one line the notice shows), an `Effect` (a native
  `EntityEffect` held while the tier is active: the set's look, authored `Infinite` with
  `OverlapBehavior Ignore`; the id must be DEDICATED to the set, since the engine takes it off
  whenever no active tier wants it, whoever put it on) and `StatModifiers`, the NATIVE item block
  byte for byte
  (`{ "<Stat>": [ { "Amount", "CalculationType", "Target" } ] }`) decoded through the library's own
  [`StatModifierSpec`](gearset/StatModifierSpec.java) leaf rather than the engine's
  `StaticModifier.CODEC` (which matches `CalculationType` only in its exact spelling, requires it,
  and gives the Asset Editor no line per word and no default); here the two words are closed
  `EditorSchema.oneOfDocumented` dropdowns, matched without regard to case, an unknown word failing
  the read; unauthored reads `Additive` / `Max` / `0`, and `toModifier()` builds the engine's own
  `StaticModifier`) inside an
  `InheritMapCodec` so a `$Comment` may sit in the map. Under `Parent`, `Bonuses` REPLACES
  wholesale and every other leaf merges. A set with no weapon authors `{Pieces 2}`, `{Pieces 3}`,
  `{Armor 4, Effect}`; one with a weapon `{Pieces 2}`, `{Armor 4, Effect}`, `{Armor 4, Held 1}`.
  [`GearSetConfig`](gearset/GearSetConfig.java) is the `defaults < pack < owner` fold, which also
  owns the derived [`GearSetIndex`](gearset/GearSetIndex.java) (item id to enabled sets, dropped by
  its own merge methods, and asked one lookup per item the player has on by `candidates`; its
  `allEffectIds` names the looks of EVERY folded set, a disabled one's included, because an
  `Infinite` effect is saved with the player and a set switched off while its wearer was offline
  must still come off at their login; a set DELETED from the fold names nothing, so a player offline
  in it when the file went keeps its look);
  [`GearSetOwnerLayers`](gearset/GearSetOwnerLayers.java) reads
  `mods/ziggfreedcommon/gear-sets.json` through the shared `OwnerLayerReader`. Registered by the
  root's `FrameworkAssetRegistrar` with no `loadsAfter` (ids resolve at recompute, never at load);
  the merge reloads the owner file and runs every online player through the bridge again.
- **[`GearSets`](gearset/GearSets.java)** - the engine facade: `install(bridge)` (the one stored
  static-final listener, so a re-run is a no-op under the bridge's identity dedup),
  `effects(apply, remove, has)` (fills the seam below), `recompute(store, ref)` (the listener
  body), `onContentChanged` / `recomputeAllOnline` (each player on their own world thread through
  the bridge's `recomputeAll`), `onPlayerReady` (LATE priority, the bridge's own hydrate),
  `onRespawned` (the same recompute, deferred to the world thread after a respawn), the two
  evictions (`onPlayerDisconnect`, `onEntityRemoved`), and the pure `flips(before, now)` /
  `announcements(previous, active)` / `shownEffects(desired, dead)` /
  `effectChanges(previous, shown, has)`. One recompute: read the
  `EquippedSnapshot`; for each candidate set (`GearSetIndex.candidates`) count the slots and pick
  the active tiers ([`GearSetDecision`](gearset/GearSetDecision.java), pure); resolve each active
  tier's block to stat indices (`StatIndexCache`; an unknown channel is named once and skipped);
  diff against the map ([`GearSetPlan`](gearset/GearSetPlan.java), pure, over the bridge's public
  `planUtility`, keys `zigset:<setId>:<tierIndex>:<offset>` from
  [`GearSetKeys`](gearset/GearSetKeys.java), the tier addressed by its POSITION since two may share a
  count); put and remove through `putModifier` / `removeModifier`, never `setStatValue`; settle the
  look through the seam by **the effect rule**: take off every effect it answers for that no active
  tier wants, and put on every effect an active tier wants that the entity does not have RIGHT NOW
  (`has`, asked of the entity, never of the row), none at all while the player is dead
  (`DeathComponent` present, the engine's own posture for a corpse); remember what was written in
  the transient `GearSetApplied` table (keyed by PLAYER alone: the stat map and the active effects
  travel with the player's component holder on a world change and nothing clears them there, so
  the row stays true and a recompute in the next world announces nothing; evicted on disconnect and
  again when the entity leaves its store for any reason but `UNLOAD`, since the disconnect event
  fires before the entity leaves and a recompute already queued could write the row back;
  `WorldEvictors` serves the engine only as `worldOf`, the thread a recompute runs on); announce
  each tier that really flipped. **Death and respawn**: the engine clears every effect on both
  (`DeathSystems.ClearEntityEffects`, `RespawnSystems.ClearEntityEffectsRespawnSystem`) while the row
  keeps listing the look; [`GearSetLifecycleSystems`](gearset/GearSetLifecycleSystems.java)
  `.Respawned` (a `RespawnSystems.OnRespawnSystem`, the `DeathComponent` coming off) hands the
  player to `onRespawned`, whose deferred recompute finds the look missing and puts it back with no
  notice, since no tier flipped; `.Left` (a `RefSystem` on `PlayerRef`) is the second eviction.
  Both registered by `EntityBootstrap.installGearSets`. **The first recompute after login is a
  hydrate, not a flip**: with no row in the table the sweep reads every `zigset:` key actually
  present on the stat map (`EntityStatValue.getModifiers()`), so a modifier stranded by a previous
  boot goes whatever set it belonged to; the effects it answers for are every effect id any folded
  set names (why an `Effect` id must be dedicated), and nothing is announced. Nothing here can
  touch another writer's key: every prefix comes from a `TierRef` and
  every stray is filtered on `GearSetKeys.isOurs`, which `GearSetPlanTest` pins by recording every
  key the plan asks about.
- **[`GearSetEffects`](gearset/GearSetEffects.java)** - the SEAM for a tier's `Effect`: `Apply` /
  `Remove` / `Has` repeat `NativeEffectUtil`'s signatures byte for byte, and the wiring root fills
  them with the three method references (zc-effects is out of this module's reach). Unfilled (any
  of the three missing) it reports on
  itself ONCE, the first time it is consulted (the `EncounterSeams.warnOnce` shape), naming the fill
  and what it costs the player: stats and notices still work, only the look is missing.
- **[`GearSetEvents`](gearset/GearSetEvents.java)** +
  **[`ZigGearSetTierChangedEvent`](gearset/ZigGearSetTierChangedEvent.java)** - the native event
  family (one `NativeEventSeam`, the `FlairEvents` shape, `publishTo` for a harness): fired ONLY on a
  real flip, deactivations before activations, carrying the player's live `PlayerRef`, the set and
  tier ids, `active`, `pieces` of `members`, and the two authored keys (`setName()` / `tierLine()`
  resolve them through `ContentKeys`, the id as plain text when a set names none). The root's
  `gearset/GearSetNoticeBridge` answers it with `FeedbackEngine.fire("Gear_Set_Tier", ...)` (args
  `source` and `set`, `name`, `pieces`, `total`, `desc`, `active`); zc-presentation ships the
  neutral `FeedbackMoments/Gear_Set_Tier.json` (Reward tone, a quiet Info variant for
  `active: false`) and the three `gearset.*` keys in `ziggfreedcommon.feedback.lang`.
- **[`GearSetValidator`](gearset/GearSetValidator.java)** - `audit()` under the `gear_set` domain,
  a disabled set skipped: ERROR `EMPTY_MEMBERS` / `NO_BONUSES` / `TIER_WITHOUT_CONDITION` /
  `BAD_PIECE_COUNT` (a minimum at or below zero, `Pieces` or `Armor` above the member count, `Held`
  or `Utility` above one); WARN `UNKNOWN_SET_MEMBER` (the loaded items, matched without regard to
  case the way members match at runtime), `UNKNOWN_STAT_ID`
  (`StatIndexCache`), `DUPLICATE_TIER` (two tiers with identical conditions),
  `MULTIPLICATIVE_ON_BASE_ZERO` (the code string a consumer's item audit already uses, over the channel's base `Max`),
  `UNKNOWN_SET_EFFECT`, `TOO_FEW_MEMBERS` (one distinct member); INFO `UNNAMED_SET`. The pure core
  takes the four lookups as functions; a consumer folds `audit()` into its own content audit.
- **Tooltip rule (content-level, no code)**: an item names its set in its own
  `items.<Id>.description`; no per-instance `ItemDisplayMetadata` write (it stops stacking,
  re-enters the bridge's content trigger and lies to the next holder). The live count is the
  notice's job.

## Conventions specific to this package

- **Numeric convention**: `StackStats.Entries` follows ONE rule -
  percent-family channels store WHOLE PERCENT POINTS (`10.0` = +10%), flat channels store their
  raw number. This package does not know which channel is which family; that classification lives
  with whoever owns the channel id (a consumer's own docs/constants).
- **Never write to the `EntityStatMap` outside a keyed `putModifier`/`removeModifier` call** - no
  class here ever calls `setStatValue`/`addStatValue` (that would mutate the CURRENT value, not a
  modifier bound, and would not diff/sweep cleanly on the next recompute).
- **A consumer subclasses NOTHING in this package.** `StackStats`/`StatMirror`/`StatChannelAudit` are
  plain static/data classes, and `EquipStatBridge`'s three trigger bases are subclassed once by the
  library itself in `EntityBootstrap.installEquipStatBridge` (the class-keyed-registry reason above is
  exactly why there is only one set); a consumer with post-apply work of its own registers an
  `AppliedListener` on the installed bridge instead, exactly as the library's own gear-set engine does.
- **Every gear-set key starts with `zigset:`**, and the engine never probes or removes a key that
  does not; the bridge's `ziggfreedcommon:` keys and a consumer's own are out of its reach by
  construction, not by care.
- **No MMO vocabulary in `gearset/`**: a stat id, an item id and an effect id are whatever the
  content names; tests and examples use the engine's own channels (`Health`, `Stamina`, `Mana`).
