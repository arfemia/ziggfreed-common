package com.ziggfreed.common.stats;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.IntFunction;
import java.util.function.ToIntFunction;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.EntityEventSystem;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.asset.type.item.config.ItemUtility;
import com.hypixel.hytale.server.core.event.events.ecs.InventoryChangeEvent;
import com.hypixel.hytale.server.core.event.events.ecs.InventorySetActiveSlotEvent;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.inventory.transaction.Transaction;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatsModule;
import com.hypixel.hytale.server.core.modules.entitystats.modifier.Modifier;
import com.hypixel.hytale.server.core.modules.entitystats.modifier.StaticModifier;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.CommonLog;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;

/**
 * Converts a player's item-carried stats into keyed native {@link EntityStatMap} modifiers so the
 * native map becomes the ONE aggregation authority - a consumer's {@code resolve*} seams read only
 * native channels, no at-use metadata fold. Covers FOUR sources with a deliberate double-apply
 * partition (decision 44/45):
 *
 * <ul>
 *   <li><b>HELD item</b>: its per-stack {@link StackStats#getEntries()} (keys {@code
 *   "<ns>:held:0"}) PLUS its item asset's native {@code Utility.StatModifiers}
 *   ({@link ItemUtility#getStatModifiers()}, keys {@code "<ns>:util:<i>"}) - the util block is
 *   applied here BECAUSE the engine never applies a HELD item's own Utility stats (proven at
 *   {@code StatModifiersManager#recalculateEntityStatModifiers}: Weapon_N is read off the held
 *   item, but Utility_N is read off the SEPARATE accessory-slot item, never a held tool's own
 *   Utility field), so a tool authoring {@code Utility.StatModifiers} (with {@code
 *   Usable}/{@code Compatible} left default-false) is a side-effect-free stat surface the bridge
 *   applies;</li>
 *   <li><b>ARMOR</b>: per-stack {@link StackStats} only (keys {@code "<ns>:armor:<i>"}) - armor
 *   asset stats are the engine's native {@code ItemArmor.StatModifiers}, never touched here;</li>
 *   <li><b>UTILITY-SLOT ACTIVE item</b> (the offhand): per-stack {@link StackStats} ONLY (keys
 *   {@code "<ns>:offhand:0"}) - NEVER its asset {@code Utility.StatModifiers}, because the engine
 *   DOES apply those natively for the utility-slot item ({@code Compatible}-gated), so applying
 *   them again would double-count.</li>
 * </ul>
 *
 * <p><b>Double-apply rule (the load-bearing invariant).</b> The held item's Utility stats are the
 * bridge's job (engine skips them for a held item); the utility-slot item's Utility stats are the
 * engine's job (it applies them natively). The bridge therefore applies a held item's asset
 * Utility stats but a utility-slot item's per-stack {@link StackStats} only. Getting this backwards
 * either drops held tool stats or double-counts offhand stats.
 *
 * <p><b>Consumer contract.</b> {@link #install(String)} (optionally with an {@link EntryFilter})
 * returns a bound {@code EquipStatBridge} instance carrying the consumer's namespace. The
 * consumer:
 * <ol>
 *   <li>registers ONE of its own concrete subclasses of {@link ActiveSlotTrigger}, {@link
 *   ContentChangeTrigger}, and {@link UtilityContentChangeTrigger} (each constructed with the
 *   bridge instance) via its own {@code getEntityStoreRegistry().registerSystem(...)} - Hytale's
 *   ECS system registry is CLASS-KEYED (a second {@code registerSystem} call with the same Class
 *   collides), so this package ships only the abstract bases, exactly like {@code
 *   cast.AbstractWorldFrameSystem}; a shared concrete class would collide the moment two different
 *   consumers (or two different namespaces) both instantiated it;</li>
 *   <li>calls {@link #recomputeAll(Store, Ref)} once at {@code PlayerReadyEvent} (inventory
 *   components are ensured/hydrated strictly before that event fires - E6-proven - so a full
 *   recompute there is safe and is the hydrate authority).</li>
 * </ol>
 *
 * <p>A consumer keeping DERIVED state in step with these channels registers an {@link
 * AppliedListener} via {@link #addAppliedListener} rather than re-deriving the equip triggers: the
 * seam fires after every recompute, so every equip path the bridge already watches is covered once.
 * What the entity has on at that moment is read back through {@link #equippedSnapshot}, the same
 * container reads the stat walkers use, so a listener deciding by WHICH items are worn (the gear-set
 * engine) never re-derives the slot layout.
 *
 * <p><b>Triggers</b> (E6-proven, non-deprecated ONLY): {@link ActiveSlotTrigger} mirrors {@code
 * InventorySystems.ActiveSlotChangedEntityEventSystem} (fires on {@link
 * InventorySetActiveSlotEvent} for ANY section - hotbar OR the utility section id {@code -5} - and
 * recomputes every source, so a utility active-slot switch is already covered); {@link
 * ContentChangeTrigger} mirrors the per-tick-drained {@link InventoryChangeEvent} filtered to the
 * Hotbar (active slot modified) or Armor component; {@link UtilityContentChangeTrigger} is the
 * same twin filtered to the {@link InventoryComponent.Utility} component (the offhand content
 * changed under the player). NEVER the deprecated {@code LegacyHotbarChangeStatSystem}/{@code
 * LegacyUtilityChangeStatSystem} (read only as precedent, per the repo edict against calling a
 * deprecated engine API).
 *
 * <p><b>Key scheme + apply.</b> The {@link StackStats} sources ({@code held}/{@code armor}/{@code
 * offhand}) each resolve to at most one additive {@code MAX} {@link StaticModifier} per stat and
 * apply through {@link #plan} (the pure decision core). The held-item {@code util} source is a
 * native {@code Int2ObjectMap<StaticModifier[]>} of PRE-RESOLVED indices carrying FULL fidelity
 * (Target MIN/MAX + ADDITIVE/MULTIPLICATIVE pass straight through - {@code putModifier} takes the
 * {@link Modifier} directly) and applies through {@link #planUtility}, mirroring the engine's own
 * {@code StatModifiersManager.addItemStatModifiers} diff-skip + stale-key sweep, keyed {@code
 * "<ns>:util:<offset>"} per stat index (a stat index may carry several modifiers). {@link #plan}
 * is package-private; {@link #planUtility} and its {@link UtilityPlan} / {@link UtilityPut} /
 * {@link StatKey} types are PUBLIC, a contract since 2.2.0, because the gear-set engine diffs every
 * tier through the same core.
 *
 * <p>Unknown stat id (channel not registered): skip + one-time warn, never a throw (the same
 * fail-closed discipline as the library's own {@code FactorRegistry}, where an unknown id is named
 * once and answers nothing rather than failing the caller). All world-thread, try-guarded.
 */
public final class EquipStatBridge {

    /** Optional per-namespace channel exclusion hook, e.g. so a future consumer can partition. */
    @FunctionalInterface
    public interface EntryFilter {
        boolean test(@Nonnull String namespace, @Nonnull String statId);
    }

    /**
     * Notified after a completed {@link #recomputeAll} apply pass, on the world thread, with the
     * entity whose modifiers were just written. The generic post-apply seam: a consumer that keeps
     * DERIVED state in step with the bridge's channels (an effect mirroring a resistance channel, a
     * HUD line, a cached fold) hangs it here instead of re-deriving the equip triggers, so it
     * cannot miss an equip path the bridge already watches.
     *
     * <p>Contract: cheap and idempotent - it runs on every slot switch and content change. A
     * throwing listener is isolated (logged, the remaining listeners still run), so one consumer's
     * failure never leaves another's state stale.
     */
    @FunctionalInterface
    public interface AppliedListener {
        void onApplied(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref);
    }

    @Nonnull
    private static final Set<String> WARNED_UNKNOWN_STATS = ConcurrentHashMap.newKeySet();

    @Nonnull
    private final String namespace;

    @Nullable
    private final EntryFilter entryFilter;

    /** Post-apply listeners, in registration order. Copy-on-write: read on every recompute. */
    @Nonnull
    private final List<AppliedListener> appliedListeners = new CopyOnWriteArrayList<>();

    private EquipStatBridge(@Nonnull String namespace, @Nullable EntryFilter entryFilter) {
        this.namespace = namespace;
        this.entryFilter = entryFilter;
    }

    /** Bind the bridge to {@code namespace}, no channel exclusion. */
    @Nonnull
    public static EquipStatBridge install(@Nonnull String namespace) {
        return new EquipStatBridge(namespace, null);
    }

    /** Bind the bridge to {@code namespace} with an {@link EntryFilter} excluding some channels. */
    @Nonnull
    public static EquipStatBridge install(@Nonnull String namespace, @Nullable EntryFilter entryFilter) {
        return new EquipStatBridge(namespace, entryFilter);
    }

    @Nonnull
    public String getNamespace() {
        return namespace;
    }

    /**
     * Register a {@link AppliedListener} notified after every {@link #recomputeAll} pass on this
     * bridge instance. Dedup is by OBJECT IDENTITY: passing the exact same reference twice (a
     * listener held in a field and reused) is a no-op the second time. It does NOT dedup two
     * separately-created instances that happen to do the same thing - a fresh lambda or instance
     * method reference literal evaluated twice (a capturing one always, a non-capturing static
     * reference on some JVMs) is a new object each time and adds a second, duplicate listener. A
     * consumer whose setup can re-run on the SAME bridge instance should store the listener once
     * and pass that stored reference, not re-evaluate the lambda/method-reference expression.
     */
    public void addAppliedListener(@Nullable AppliedListener listener) {
        if (listener != null && !appliedListeners.contains(listener)) {
            appliedListeners.add(listener);
        }
    }

    /** Drop a previously registered {@link AppliedListener}; unknown listeners are ignored. */
    public void removeAppliedListener(@Nullable AppliedListener listener) {
        if (listener != null) {
            appliedListeners.remove(listener);
        }
    }

    /**
     * The live post-apply roster. Package-private on purpose: it lets a same-package test assert
     * the registration semantics ({@link #addAppliedListener} deduping by identity,
     * {@link #removeAppliedListener} dropping) against the REAL list instead of a hand-built
     * stand-in that would pass whatever the bridge did. Not part of the public surface.
     */
    @Nonnull
    List<AppliedListener> appliedListenersView() {
        return appliedListeners;
    }

    /**
     * Full recompute across all four sources: the held item's {@link StackStats}, the held item's
     * native {@code Utility.StatModifiers}, the utility-slot active item's {@link StackStats}
     * (the offhand), and every armor slot's {@link StackStats}. Safe to call on every trigger AND
     * at {@code PlayerReadyEvent}; a missing stat map (no live entity) is a silent no-op.
     */
    public void recomputeAll(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref) {
        try {
            EntityStatMap statMap = store.getComponent(ref, EntityStatsModule.get().getEntityStatMapComponentType());
            if (statMap == null) {
                return;
            }
            // HELD item: per-stack enhancement (held:*) + the item asset's own Utility.StatModifiers
            // (util:*) - the engine never applies a held item's Utility stats, so the bridge does.
            applySource(statMap, heldKey(0), heldSourceEntries(store, ref));
            applyUtilityAssetSource(statMap, heldUtilityModifiers(store, ref));
            // UTILITY-SLOT active item (offhand): per-stack enhancement ONLY (offhand:*) - the
            // engine applies THAT item's asset Utility.StatModifiers natively (Compatible-gated).
            applySource(statMap, offhandKey(0), offhandSourceEntries(store, ref));
            int armorSlots = armorCapacity(store, ref);
            for (int i = 0; i < armorSlots; i++) {
                applySource(statMap, armorKey(i), armorSourceEntries(store, ref, i));
            }
        } catch (Throwable t) {
            warn("recomputeAll", t);
        }
        // Post-apply seam, OUTSIDE the apply try so a listener still runs after a partial apply -
        // derived state tracking a channel is more wrong when it is stale than when it is late.
        forEachIsolated(appliedListeners, l -> l.onApplied(store, ref), "appliedListener");
    }

    /**
     * The bridge's OWN current per-channel contribution to {@code statId}, summed across ALL
     * bridge namespaces - the held {@link StackStats}, the held item's native {@code
     * Utility.StatModifiers} (ADDITIVE contributions), the utility-slot (offhand) {@link
     * StackStats}, and every armor slot's {@link StackStats} (gate decision 35: a DOT branch
     * subtracts this from the attacker fold so per-stack enhancement never buffs a DOT tick,
     * matching the pre-migration behavior where the DOT path never read held metadata at all). The
     * util contribution is inherited automatically here, so a DOT-relevant stat authored as a held
     * tool's Utility block is excluded from DOTs too. Re-derives the sources fresh rather than
     * reading the {@link EntityStatMap} back, so it is accurate even before the first {@link
     * #recomputeAll} apply and respects the same {@link EntryFilter}.
     *
     * <p>Only ADDITIVE util modifiers are summed - a MULTIPLICATIVE modifier has no linear scalar
     * a DOT subtraction could use, so a channel a consumer subtracts this way is meant to be
     * authored additive.
     */
    public double bridgedSum(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
            @Nonnull String statId) {
        try {
            double sum = matchingAmount(heldSourceEntries(store, ref), statId);
            sum += utilityAssetMatchingAmount(store, ref, statId);
            sum += matchingAmount(offhandSourceEntries(store, ref), statId);
            int armorSlots = armorCapacity(store, ref);
            for (int i = 0; i < armorSlots; i++) {
                sum += matchingAmount(armorSourceEntries(store, ref, i), statId);
            }
            return sum;
        } catch (Throwable t) {
            warn("bridgedSum", t);
            return 0.0;
        }
    }

    private double matchingAmount(@Nonnull Map<String, Double> entries, @Nonnull String statId) {
        if (entryFilter != null && !entryFilter.test(namespace, statId)) {
            return 0.0;
        }
        Double v = entries.get(statId);
        return v != null ? v : 0.0;
    }

    /** The summed ADDITIVE amount the held item's {@code Utility.StatModifiers} contributes to {@code statId}. */
    private double utilityAssetMatchingAmount(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
            @Nonnull String statId) {
        Int2ObjectMap<StaticModifier[]> mods = heldUtilityModifiers(store, ref);
        if (mods == null) {
            return 0.0;
        }
        int idx = StatIndexCache.resolve(statId);
        if (idx < 0) {
            return 0.0;
        }
        StaticModifier[] arr = mods.get(idx);
        if (arr == null) {
            return 0.0;
        }
        double s = 0.0;
        for (StaticModifier m : arr) {
            if (m != null && m.getCalculationType() == StaticModifier.CalculationType.ADDITIVE) {
                s += m.getAmount();
            }
        }
        return s;
    }

    // ---- what the entity has on ----

    /**
     * What the entity has on right now, by item id: the held stack, the utility-slot (offhand)
     * stack and every armor slot, read through the SAME container reads the stat walkers above use,
     * so a listener deciding by which items are worn sees exactly the slots the bridge applies. An
     * empty slot, a missing container or an invalid ref reads as nothing there; never a throw.
     * World thread, like every read here.
     */
    @Nonnull
    public static EquippedSnapshot equippedSnapshot(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref) {
        try {
            List<String> armor = new ArrayList<>();
            int armorSlots = armorCapacity(store, ref);
            for (int i = 0; i < armorSlots; i++) {
                armor.add(itemIdOf(armorStack(store, ref, i)));
            }
            return EquippedSnapshot.of(itemIdOf(heldStack(store, ref)), itemIdOf(offhandStack(store, ref)), armor);
        } catch (Throwable t) {
            warn("equippedSnapshot", t);
            return EquippedSnapshot.EMPTY;
        }
    }

    /** A stack's item id, or null for no stack, an empty one, or one with no id. */
    @Nullable
    private static String itemIdOf(@Nullable ItemStack stack) {
        if (stack == null || ItemStack.isEmpty(stack)) {
            return null;
        }
        String id = stack.getItemId();
        return id == null || id.isBlank() ? null : id;
    }

    // ---- the container reads every source shares ----

    /** The held stack, the engine's own item-in-hand read; null when nothing is held. */
    @Nullable
    private static ItemStack heldStack(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref) {
        return InventoryComponent.getItemInHand(store, ref);
    }

    /** The utility-slot ACTIVE stack (the offhand); null without a utility container or an active item. */
    @Nullable
    private static ItemStack offhandStack(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref) {
        InventoryComponent.Utility utility = store.getComponent(ref, InventoryComponent.Utility.getComponentType());
        return utility == null ? null : utility.getActiveItem();
    }

    /** The stack in armor slot {@code slot}; null without an armor container, past its capacity, or empty. */
    @Nullable
    private static ItemStack armorStack(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref, int slot) {
        InventoryComponent.Armor armor = store.getComponent(ref, InventoryComponent.Armor.getComponentType());
        if (armor == null) {
            return null;
        }
        ItemContainer container = armor.getInventory();
        if (container == null || slot >= container.getCapacity()) {
            return null;
        }
        return container.getItemStack((short) slot);
    }

    private static int armorCapacity(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref) {
        InventoryComponent.Armor armor = store.getComponent(ref, InventoryComponent.Armor.getComponentType());
        if (armor == null) {
            return 0;
        }
        ItemContainer container = armor.getInventory();
        return container != null ? container.getCapacity() : 0;
    }

    // ---- sources ----

    /** A stack's per-stack {@link StackStats} entries; empty for no stack or no record. */
    @Nonnull
    private static Map<String, Double> stackEntries(@Nullable ItemStack stack) {
        if (stack == null) {
            return Collections.emptyMap();
        }
        Map<String, Double> entries = StackStats.entriesOf(stack);
        return entries != null ? entries : Collections.emptyMap();
    }

    @Nonnull
    private static Map<String, Double> heldSourceEntries(@Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ref) {
        return stackEntries(heldStack(store, ref));
    }

    /**
     * The held item's asset-authored {@code Utility.StatModifiers} (pre-resolved index map), or
     * {@code null} when nothing is held / the item has no asset / no utility stats. The engine
     * never applies these for a held item, so the bridge owns them.
     */
    @Nullable
    private static Int2ObjectMap<StaticModifier[]> heldUtilityModifiers(@Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ref) {
        ItemStack stack = heldStack(store, ref);
        if (stack == null || ItemStack.isEmpty(stack)) {
            return null;
        }
        Item item = stack.getItem();
        if (item == null) {
            return null;
        }
        ItemUtility utility = item.getUtility();
        return utility != null ? utility.getStatModifiers() : null;
    }

    /** The utility-slot ACTIVE item's per-stack {@link StackStats} entries (the offhand source). */
    @Nonnull
    private static Map<String, Double> offhandSourceEntries(@Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ref) {
        return stackEntries(offhandStack(store, ref));
    }

    @Nonnull
    private static Map<String, Double> armorSourceEntries(@Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ref, int slot) {
        return stackEntries(armorStack(store, ref, slot));
    }

    // ---- key scheme ----

    @Nonnull
    private String heldKey(int slot) {
        return namespace + ":held:" + slot;
    }

    @Nonnull
    private String armorKey(int slot) {
        return namespace + ":armor:" + slot;
    }

    @Nonnull
    private String offhandKey(int slot) {
        return namespace + ":offhand:" + slot;
    }

    @Nonnull
    private String utilityKeyPrefix() {
        return namespace + ":util:";
    }

    // ---- apply (diff-skip + stale-key sweep) ----

    private void applySource(@Nonnull EntityStatMap statMap, @Nonnull String key, @Nonnull Map<String, Double> entries) {
        Plan p = plan(entries, entryFilter, namespace, StatIndexCache::resolve,
                idx -> existingAmount(statMap, idx, key), statMap.size(), EquipStatBridge::warnUnknownStatOnce);
        for (Map.Entry<Integer, Float> put : p.puts.entrySet()) {
            StaticModifier mod = new StaticModifier(Modifier.ModifierTarget.MAX, StaticModifier.CalculationType.ADDITIVE,
                    put.getValue());
            statMap.putModifier(put.getKey(), key, mod);
        }
        for (int idx : p.removes) {
            statMap.removeModifier(idx, key);
        }
    }

    /**
     * Apply the held item's native {@code Utility.StatModifiers} under {@code "<ns>:util:<offset>"}
     * keys with FULL fidelity - each {@link StaticModifier} passes straight through {@code
     * putModifier} (its Target MIN/MAX + ADDITIVE/MULTIPLICATIVE preserved). {@link #planUtility}
     * is the pure decision core; the {@link EntryFilter} does NOT apply here (this source is keyed
     * by pre-resolved stat INDEX, not id, and no shipping consumer uses a filter).
     */
    private void applyUtilityAssetSource(@Nonnull EntityStatMap statMap,
            @Nullable Int2ObjectMap<StaticModifier[]> mods) {
        String prefix = utilityKeyPrefix();
        UtilityPlan p = planUtility(mods, prefix, statMap.size(),
                (idx, key) -> existingStatic(statMap, idx, key));
        for (UtilityPut put : p.puts) {
            statMap.putModifier(put.statIndex, put.key, put.modifier);
        }
        for (StatKey rk : p.removes) {
            statMap.removeModifier(rk.statIndex, rk.key);
        }
    }

    @Nullable
    private static Float existingAmount(@Nonnull EntityStatMap statMap, int idx, @Nonnull String key) {
        Modifier existing = statMap.getModifier(idx, key);
        if (existing instanceof StaticModifier sm
                && sm.getCalculationType() == StaticModifier.CalculationType.ADDITIVE
                && sm.getTarget() == Modifier.ModifierTarget.MAX) {
            return sm.getAmount();
        }
        return null;
    }

    @Nullable
    private static StaticModifier existingStatic(@Nonnull EntityStatMap statMap, int idx, @Nonnull String key) {
        Modifier existing = statMap.getModifier(idx, key);
        return existing instanceof StaticModifier sm ? sm : null;
    }

    /**
     * PURE decision core (package-private, unit-testable without a live {@link EntityStatMap}):
     * given the resolved source entries for ONE key, decide which stat indices to put/update
     * (a NEW or CHANGED amount), and which to sweep (this key is present on a stat index the
     * current entries no longer touch - including one never touched this apply at all).
     */
    static final class Plan {
        @Nonnull
        final Map<Integer, Float> puts;
        @Nonnull
        final Set<Integer> removes;

        Plan(@Nonnull Map<Integer, Float> puts, @Nonnull Set<Integer> removes) {
            this.puts = puts;
            this.removes = removes;
        }
    }

    @Nonnull
    static Plan plan(@Nonnull Map<String, Double> entries,
            @Nullable EntryFilter entryFilter,
            @Nonnull String namespace,
            @Nonnull ToIntFunction<String> indexResolver,
            @Nonnull IntFunction<Float> existingAmountLookup,
            int statMapSize,
            @Nonnull Consumer<String> onUnknownStat) {
        Map<Integer, Float> puts = new LinkedHashMap<>();
        Set<Integer> touched = new HashSet<>();
        for (Map.Entry<String, Double> e : entries.entrySet()) {
            String statId = e.getKey();
            Double amount = e.getValue();
            if (statId == null || amount == null) {
                continue;
            }
            if (entryFilter != null && !entryFilter.test(namespace, statId)) {
                continue;
            }
            int idx = indexResolver.applyAsInt(statId);
            if (idx < 0) {
                onUnknownStat.accept(statId);
                continue;
            }
            touched.add(idx);
            float newAmount = amount.floatValue();
            Float existing = existingAmountLookup.apply(idx);
            if (existing != null && existing.floatValue() == newAmount) {
                continue; // diff-skip: already the correct modifier
            }
            puts.put(idx, newAmount);
        }
        Set<Integer> removes = new HashSet<>();
        for (int i = 0; i < statMapSize; i++) {
            if (!touched.contains(i)) {
                removes.add(i);
            }
        }
        return new Plan(puts, removes);
    }

    // ---- the prefixed-source pure apply core (PUBLIC 2.2.0 contract) ----
    // One diff core serves the held item's native Utility.StatModifiers here and every gear-set
    // tier's block in stats/gearset, so the two can never disagree about what "stale" means. The
    // types and planUtility below are public and frozen with 2.2.0; plan(...) above stays
    // package-private, since its per-slot StackStats keying is this class's own business.

    /**
     * One planned WRITE: put {@link #modifier} on the stat at {@link #statIndex} under {@link #key}
     * ({@code EntityStatMap.putModifier(statIndex, key, modifier)}). Immutable; the modifier is
     * carried through untouched, so its {@code Target} and {@code CalculationType} reach the map as
     * authored.
     *
     * @since 2.2.0
     */
    public static final class UtilityPut {
        public final int statIndex;
        @Nonnull
        public final String key;
        @Nonnull
        public final StaticModifier modifier;

        /**
         * @param statIndex the stat's index in the entity's {@code EntityStatMap}
         * @param key       the full modifier key, the source's prefix plus the array offset
         * @param modifier  the modifier to put, as authored
         */
        public UtilityPut(int statIndex, @Nonnull String key, @Nonnull StaticModifier modifier) {
            this.statIndex = statIndex;
            this.key = key;
            this.modifier = modifier;
        }
    }

    /**
     * One modifier ADDRESS on an entity's stat map: the stat's index and the modifier key under it.
     * A plan's removals are these ({@code EntityStatMap.removeModifier(statIndex, key)}), and a
     * caller may hold them as values: two addresses are equal exactly when both the index and the
     * key are, and the hash agrees. Immutable.
     *
     * @since 2.2.0
     */
    public static final class StatKey {
        public final int statIndex;
        @Nonnull
        public final String key;

        /**
         * @param statIndex the stat's index in the entity's {@code EntityStatMap}
         * @param key       the full modifier key
         */
        public StatKey(int statIndex, @Nonnull String key) {
            this.statIndex = statIndex;
            this.key = key;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof StatKey other)) {
                return false;
            }
            return statIndex == other.statIndex && key.equals(other.key);
        }

        @Override
        public int hashCode() {
            return 31 * statIndex + key.hashCode();
        }
    }

    /**
     * What one {@link #planUtility} call decided: the {@link #puts} to write (a modifier already
     * equal under its key is left out, the diff-skip) and the {@link #removes} to sweep (every
     * address under the prefix that the desired block no longer writes). The two never share an
     * address. A caller applies the puts and then the removes, in any order within each.
     *
     * @since 2.2.0
     */
    public static final class UtilityPlan {
        @Nonnull
        public final List<UtilityPut> puts;
        @Nonnull
        public final Set<StatKey> removes;

        /**
         * @param puts    the writes
         * @param removes the addresses to sweep
         */
        public UtilityPlan(@Nonnull List<UtilityPut> puts, @Nonnull Set<StatKey> removes) {
            this.puts = puts;
            this.removes = removes;
        }
    }

    /**
     * The PURE diff for one PREFIXED modifier source: what to put and what to sweep so that, after
     * the caller applies the plan, the entity carries exactly {@code mods} under {@code keyPrefix}
     * and nothing else under it. The held item's native {@code Utility.StatModifiers} goes through
     * here, and so does every gear-set tier's block under its own {@code zigset:} prefix; a consumer
     * with a prefixed source of its own may use it the same way.
     *
     * <p><b>Keys.</b> Mirroring the engine's own {@code StatModifiersManager.addItemStatModifiers},
     * the modifiers at one stat index are keyed by their position among that index's non-null
     * entries: {@code keyPrefix + 0}, {@code keyPrefix + 1}, and so on. A null entry takes no
     * offset.
     *
     * <p><b>Puts.</b> One {@link UtilityPut} per non-null modifier, except where
     * {@code existingLookup} already answers an EQUAL modifier under that address (the diff-skip,
     * so a repeated call on an unchanged entity writes nothing).
     *
     * <p><b>Removes.</b> At a stat index {@code mods} names, every key past its last offset that
     * {@code existingLookup} still answers (a previously longer array); at every other index below
     * {@code statMapSize}, every key from offset 0 up. Each sweep probes offsets upward and stops at
     * the first gap, so keys are expected contiguous from 0, which is how this method writes them.
     * A null {@code mods} is therefore a pure sweep: every key under the prefix, on every index,
     * goes.
     *
     * <p><b>Nothing outside the prefix.</b> Every address probed and every address returned starts
     * with {@code keyPrefix}; a caller choosing a prefix no other writer uses owns its keys outright.
     * No live {@link EntityStatMap} is touched and nothing is thrown: the caller reads, the caller
     * writes, on the world thread.
     *
     * @param mods           the desired block, stat index to its modifiers; null for none
     * @param keyPrefix      the source's key prefix, every key this call reads or plans starts with it
     * @param statMapSize    how many stat indices the entity's map has ({@code EntityStatMap.size()})
     * @param existingLookup the {@link StaticModifier} currently under (statIndex, key), or null when
     *                       there is none (or what is there is not a {@code StaticModifier})
     * @return the writes and the sweeps, never null
     * @since 2.2.0
     */
    @Nonnull
    public static UtilityPlan planUtility(@Nullable Int2ObjectMap<StaticModifier[]> mods,
            @Nonnull String keyPrefix,
            int statMapSize,
            @Nonnull BiFunction<Integer, String, StaticModifier> existingLookup) {
        List<UtilityPut> puts = new ArrayList<>();
        Set<StatKey> removes = new HashSet<>();
        Set<Integer> present = new HashSet<>();
        if (mods != null) {
            for (Int2ObjectMap.Entry<StaticModifier[]> e : mods.int2ObjectEntrySet()) {
                int statIndex = e.getIntKey();
                present.add(statIndex);
                StaticModifier[] arr = e.getValue();
                int offset = 0;
                if (arr != null) {
                    for (StaticModifier modifier : arr) {
                        if (modifier == null) {
                            continue;
                        }
                        String key = keyPrefix + offset;
                        offset++;
                        StaticModifier existing = existingLookup.apply(statIndex, key);
                        if (existing != null && existing.equals(modifier)) {
                            continue; // diff-skip
                        }
                        puts.add(new UtilityPut(statIndex, key, modifier));
                    }
                }
                // Sweep leftover higher-offset keys (the previous array at this index was longer).
                int probe = offset;
                while (existingLookup.apply(statIndex, keyPrefix + probe) != null) {
                    removes.add(new StatKey(statIndex, keyPrefix + probe));
                    probe++;
                }
            }
        }
        // Sweep every stat index the new map does not touch at all.
        for (int i = 0; i < statMapSize; i++) {
            if (present.contains(i)) {
                continue;
            }
            int probe = 0;
            while (existingLookup.apply(i, keyPrefix + probe) != null) {
                removes.add(new StatKey(i, keyPrefix + probe));
                probe++;
            }
        }
        return new UtilityPlan(puts, removes);
    }

    private static void warnUnknownStatOnce(@Nonnull String statId) {
        if (WARNED_UNKNOWN_STATS.add(statId)) {
            try {
                CommonLog.LOGGER.atWarning().log("EquipStatBridge: unknown stat channel '" + statId
                        + "' - skipping (not registered yet, or misspelled)");
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * Run {@code action} for every listener, isolating a throwing one so the rest still run.
     * Generic over the listener type (and so unit-testable with no live store) because the
     * isolation rule, not the listener shape, is what has to hold.
     */
    static <L> void forEachIsolated(@Nonnull Iterable<L> listeners, @Nonnull Consumer<L> action,
            @Nonnull String label) {
        for (L listener : listeners) {
            try {
                action.accept(listener);
            } catch (Throwable t) {
                warn(label, t);
            }
        }
    }

    private static void warn(@Nonnull String label, @Nonnull Throwable t) {
        try {
            CommonLog.LOGGER.atFine().log("EquipStatBridge." + label + " failed: " + t.getMessage());
        } catch (Throwable ignored) {
        }
    }

    // ---- E6 triggers: abstract bases only - the ECS system registry is class-keyed, so each
    // consumer registers its OWN concrete subclass (mirrors cast.AbstractWorldFrameSystem). ----

    /**
     * Mirrors {@code InventorySystems.ActiveSlotChangedEntityEventSystem}: recomputes on every
     * active-slot switch (hotbar OR the utility section, id {@code -5}). Recomputes every source
     * unconditionally, so a utility active-slot switch is covered here without a section filter. A
     * consumer registers a small concrete subclass, e.g. {@code new
     * EquipStatBridge.ActiveSlotTrigger(bridge) {}}, via its own {@code
     * getEntityStoreRegistry().registerSystem(...)}.
     */
    public abstract static class ActiveSlotTrigger extends EntityEventSystem<EntityStore, InventorySetActiveSlotEvent> {

        @Nonnull
        private final EquipStatBridge bridge;

        protected ActiveSlotTrigger(@Nonnull EquipStatBridge bridge) {
            super(InventorySetActiveSlotEvent.class);
            this.bridge = bridge;
        }

        @Override
        public final void handle(final int index, @Nonnull final ArchetypeChunk<EntityStore> archetypeChunk,
                @Nonnull final Store<EntityStore> store, @Nonnull final CommandBuffer<EntityStore> commandBuffer,
                @Nonnull final InventorySetActiveSlotEvent event) {
            bridge.recomputeAll(store, archetypeChunk.getReferenceTo(index));
        }

        @Nullable
        @Override
        public Query<EntityStore> getQuery() {
            return InventoryComponent.Hotbar.getComponentType();
        }
    }

    /**
     * Content-mutation trigger: recomputes when the HELD stack itself changes (Hotbar, active
     * slot modified) or any armor slot changes. Filters {@link InventoryChangeEvent} the same
     * way the deprecated {@code LegacyHotbarChangeStatSystem}/{@code LegacyArmorChangeStatSystem}
     * did (read only as precedent - never called/extended, per the repo's no-deprecated-API
     * edict). A consumer registers a small concrete subclass, e.g. {@code new
     * EquipStatBridge.ContentChangeTrigger(bridge) {}}, via its own {@code
     * getEntityStoreRegistry().registerSystem(...)}.
     */
    public abstract static class ContentChangeTrigger extends EntityEventSystem<EntityStore, InventoryChangeEvent> {

        @Nonnull
        private final EquipStatBridge bridge;

        protected ContentChangeTrigger(@Nonnull EquipStatBridge bridge) {
            super(InventoryChangeEvent.class);
            this.bridge = bridge;
        }

        @Override
        public final void handle(final int index, @Nonnull final ArchetypeChunk<EntityStore> archetypeChunk,
                @Nonnull final Store<EntityStore> store, @Nonnull final CommandBuffer<EntityStore> commandBuffer,
                @Nonnull final InventoryChangeEvent event) {
            Ref<EntityStore> ref = archetypeChunk.getReferenceTo(index);
            if (!isRelevant(store, ref, event)) {
                return;
            }
            bridge.recomputeAll(store, ref);
        }

        private boolean isRelevant(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
                @Nonnull InventoryChangeEvent event) {
            if (event.getComponentType() == InventoryComponent.Armor.getComponentType()) {
                return true;
            }
            if (event.getComponentType() == InventoryComponent.Hotbar.getComponentType()) {
                InventoryComponent.Hotbar hotbar = store.getComponent(ref, InventoryComponent.Hotbar.getComponentType());
                if (hotbar == null) {
                    return false;
                }
                byte activeSlot = hotbar.getActiveSlot();
                if (activeSlot == InventoryComponent.INACTIVE_SLOT_INDEX) {
                    return false;
                }
                Transaction transaction = event.getTransaction();
                return transaction != null && transaction.wasSlotModified(activeSlot);
            }
            return false;
        }

        @Nullable
        @Override
        public Query<EntityStore> getQuery() {
            return InventoryComponent.Hotbar.getComponentType();
        }
    }

    /**
     * Content-mutation trigger for the UTILITY (offhand) section: recomputes when the utility
     * container's contents change (decision 45). Mirrors the non-deprecated {@code
     * InventorySystems.UtilityChangeEventSystem} shape (filters {@link InventoryChangeEvent} to
     * the {@link InventoryComponent.Utility} component); NEVER the deprecated {@code
     * LegacyUtilityChangeStatSystem}. Paired with {@link ActiveSlotTrigger} (which already covers
     * the utility active-slot switch), this closes the offhand's per-stack {@link StackStats}
     * path. A consumer registers a small concrete subclass, e.g. {@code new
     * EquipStatBridge.UtilityContentChangeTrigger(bridge) {}}, via its own {@code
     * getEntityStoreRegistry().registerSystem(...)}.
     */
    public abstract static class UtilityContentChangeTrigger
            extends EntityEventSystem<EntityStore, InventoryChangeEvent> {

        @Nonnull
        private final EquipStatBridge bridge;

        protected UtilityContentChangeTrigger(@Nonnull EquipStatBridge bridge) {
            super(InventoryChangeEvent.class);
            this.bridge = bridge;
        }

        @Override
        public final void handle(final int index, @Nonnull final ArchetypeChunk<EntityStore> archetypeChunk,
                @Nonnull final Store<EntityStore> store, @Nonnull final CommandBuffer<EntityStore> commandBuffer,
                @Nonnull final InventoryChangeEvent event) {
            if (event.getComponentType() != InventoryComponent.Utility.getComponentType()) {
                return;
            }
            bridge.recomputeAll(store, archetypeChunk.getReferenceTo(index));
        }

        @Nullable
        @Override
        public Query<EntityStore> getQuery() {
            return InventoryComponent.Utility.getComponentType();
        }
    }
}
