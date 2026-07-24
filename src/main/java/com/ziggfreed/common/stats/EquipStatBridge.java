package com.ziggfreed.common.stats;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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
import com.ziggfreed.common.ZiggfreedCommonPlugin;

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
 * "<ns>:util:<offset>"} per stat index (a stat index may carry several modifiers).
 *
 * <p>Unknown stat id (channel not registered): skip + one-time warn, never a throw (mirrors the
 * MMO's {@code FactorRegistry} fail-closed discipline). All world-thread, try-guarded.
 */
public final class EquipStatBridge {

    /** Optional per-namespace channel exclusion hook, e.g. so a future consumer can partition. */
    @FunctionalInterface
    public interface EntryFilter {
        boolean test(@Nonnull String namespace, @Nonnull String statId);
    }

    @Nonnull
    private static final Set<String> WARNED_UNKNOWN_STATS = ConcurrentHashMap.newKeySet();

    @Nonnull
    private final String namespace;

    @Nullable
    private final EntryFilter entryFilter;

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
     * a DOT subtraction could use, and the MMO's DOT-relevant channels are all additive.
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

    // ---- sources ----

    @Nonnull
    private static Map<String, Double> heldSourceEntries(@Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ref) {
        ItemStack stack = InventoryComponent.getItemInHand(store, ref);
        if (stack == null) {
            return Collections.emptyMap();
        }
        Map<String, Double> entries = StackStats.entriesOf(stack);
        return entries != null ? entries : Collections.emptyMap();
    }

    /**
     * The held item's asset-authored {@code Utility.StatModifiers} (pre-resolved index map), or
     * {@code null} when nothing is held / the item has no asset / no utility stats. The engine
     * never applies these for a held item, so the bridge owns them.
     */
    @Nullable
    private static Int2ObjectMap<StaticModifier[]> heldUtilityModifiers(@Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ref) {
        ItemStack stack = InventoryComponent.getItemInHand(store, ref);
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
        InventoryComponent.Utility utility = store.getComponent(ref, InventoryComponent.Utility.getComponentType());
        if (utility == null) {
            return Collections.emptyMap();
        }
        ItemStack stack = utility.getActiveItem();
        if (stack == null) {
            return Collections.emptyMap();
        }
        Map<String, Double> entries = StackStats.entriesOf(stack);
        return entries != null ? entries : Collections.emptyMap();
    }

    @Nonnull
    private static Map<String, Double> armorSourceEntries(@Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ref, int slot) {
        InventoryComponent.Armor armor = store.getComponent(ref, InventoryComponent.Armor.getComponentType());
        if (armor == null) {
            return Collections.emptyMap();
        }
        ItemContainer container = armor.getInventory();
        if (container == null || slot >= container.getCapacity()) {
            return Collections.emptyMap();
        }
        ItemStack stack = container.getItemStack((short) slot);
        if (stack == null) {
            return Collections.emptyMap();
        }
        Map<String, Double> entries = StackStats.entriesOf(stack);
        return entries != null ? entries : Collections.emptyMap();
    }

    private static int armorCapacity(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref) {
        InventoryComponent.Armor armor = store.getComponent(ref, InventoryComponent.Armor.getComponentType());
        if (armor == null) {
            return 0;
        }
        ItemContainer container = armor.getInventory();
        return container != null ? container.getCapacity() : 0;
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

    // ---- util (native Utility.StatModifiers) pure apply core ----

    /** One planned put for the util source: the full {@link StaticModifier} at (statIndex, key). */
    static final class UtilityPut {
        final int statIndex;
        @Nonnull
        final String key;
        @Nonnull
        final StaticModifier modifier;

        UtilityPut(int statIndex, @Nonnull String key, @Nonnull StaticModifier modifier) {
            this.statIndex = statIndex;
            this.key = key;
            this.modifier = modifier;
        }
    }

    /** One (statIndex, key) removal address for the util source. */
    static final class StatKey {
        final int statIndex;
        @Nonnull
        final String key;

        StatKey(int statIndex, @Nonnull String key) {
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

    static final class UtilityPlan {
        @Nonnull
        final List<UtilityPut> puts;
        @Nonnull
        final Set<StatKey> removes;

        UtilityPlan(@Nonnull List<UtilityPut> puts, @Nonnull Set<StatKey> removes) {
            this.puts = puts;
            this.removes = removes;
        }
    }

    /**
     * PURE decision core for the native {@code Utility.StatModifiers} source (package-private,
     * unit-testable with a fake {@code existingLookup} + a hand-built {@code Int2ObjectMap}, no
     * live {@link EntityStatMap}). Mirrors {@code StatModifiersManager.addItemStatModifiers}: for
     * each stat index, walk its modifier array assigning per-array-position keys {@code
     * keyPrefix + offset}, diff-skipping a modifier whose existing equals the new one; then sweep
     * (a) higher-offset keys left over from a previously-longer array at that index, and (b) every
     * key on every stat index the new map does not touch at all. Full {@link StaticModifier}
     * fidelity - the modifier is carried through untouched (Target + CalculationType + Amount).
     */
    @Nonnull
    static UtilityPlan planUtility(@Nullable Int2ObjectMap<StaticModifier[]> mods,
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
                ZiggfreedCommonPlugin.LOGGER.atWarning().log("EquipStatBridge: unknown stat channel '" + statId
                        + "' - skipping (not registered yet, or misspelled)");
            } catch (Throwable ignored) {
            }
        }
    }

    private static void warn(@Nonnull String label, @Nonnull Throwable t) {
        try {
            ZiggfreedCommonPlugin.LOGGER.atFine().log("EquipStatBridge." + label + " failed: " + t.getMessage());
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
