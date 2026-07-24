package com.ziggfreed.common.stats;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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

/**
 * Converts a stack's {@link StackStats#getEntries()} PLUS the held item's {@link
 * HeldItemStatsTag} entries into keyed native {@link EntityStatMap} modifiers, so the native map
 * becomes the ONE aggregation authority for both per-stack enhancement AND tag-authored tool
 * stats - a consumer's {@code resolve*} seams read only native channels, no at-use metadata fold.
 *
 * <p><b>Consumer contract.</b> {@link #install(String)} (optionally with an {@link EntryFilter})
 * returns a bound {@code EquipStatBridge} instance carrying the consumer's namespace. The
 * consumer:
 * <ol>
 *   <li>registers ONE of its own concrete subclasses of {@link ActiveSlotTrigger} and {@link
 *   ContentChangeTrigger} (each constructed with the bridge instance) via its own {@code
 *   getEntityStoreRegistry().registerSystem(...)} - Hytale's ECS system registry is CLASS-KEYED
 *   (a second {@code registerSystem} call with the same Class collides), so this package ships
 *   only the abstract bases, exactly like {@code cast.AbstractWorldFrameSystem}; a shared
 *   concrete class would collide the moment two different consumers (or two different
 *   namespaces) both instantiated it;</li>
 *   <li>calls {@link #recomputeAll(Store, Ref)} once at {@code PlayerReadyEvent} (inventory
 *   components are ensured/hydrated strictly before that event fires - E6-proven - so a full
 *   recompute there is safe and is the hydrate authority).</li>
 * </ol>
 *
 * <p><b>Triggers</b> (E6-proven, non-deprecated ONLY): {@link ActiveSlotTrigger} mirrors {@code
 * InventorySystems.ActiveSlotChangedEntityEventSystem} (fires on {@link
 * InventorySetActiveSlotEvent}, dispatched synchronously by {@code
 * ActiveSlotInventoryComponent.setActiveSlot}); {@link ContentChangeTrigger} mirrors the
 * per-tick-drained {@link InventoryChangeEvent}, filtered to (a) the Hotbar component with the
 * ACTIVE slot modified (the held stack itself changed under the player) or (b) the Armor
 * component (any armor slot). NEVER the deprecated {@code LegacyHotbarChangeStatSystem}/{@code
 * LegacyArmorChangeStatSystem} (read only as precedent, per the repo edict against calling a
 * deprecated engine API).
 *
 * <p><b>Key scheme + apply.</b> Per source slot-group the bridge writes an additive {@code MAX}
 * {@link StaticModifier} under {@code "<namespace>:held:<i>"} / {@code "<namespace>:armor:<i>"}
 * / {@code "<namespace>:tag:<i>"} (held and tag always use slot {@code 0} - a player has exactly
 * one active hand; armor iterates every armor-container slot). A full recompute re-derives every
 * source's entries and, per key, diff-skips a stat whose existing modifier already matches
 * (native {@link StaticModifier#equals} - target/calc-type/amount) and sweeps that key off every
 * OTHER stat the entity's {@link EntityStatMap} knows about (the "stats the new item no longer
 * touches" removal - the native {@code StatModifiersManager.clearAllStatModifiers} discipline,
 * adapted to a per-SLOT key instead of a per-stat numbered-offset key since this record carries
 * at most one amount per stat id per source). {@link #plan} is the PURE decision core behind
 * this (unit-testable against a fake seam, no live {@link EntityStatMap} needed).
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
     * Full recompute across the held item, the held item's {@link HeldItemStatsTag}, and every
     * armor slot. Safe to call on every trigger AND at {@code PlayerReadyEvent}; a missing stat
     * map (no live entity) is a silent no-op.
     */
    public void recomputeAll(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref) {
        try {
            EntityStatMap statMap = store.getComponent(ref, EntityStatsModule.get().getEntityStatMapComponentType());
            if (statMap == null) {
                return;
            }
            applySource(statMap, heldKey(0), heldSourceEntries(store, ref));
            applySource(statMap, tagKey(0), tagSourceEntries(store, ref));
            int armorSlots = armorCapacity(store, ref);
            for (int i = 0; i < armorSlots; i++) {
                applySource(statMap, armorKey(i), armorSourceEntries(store, ref, i));
            }
        } catch (Throwable t) {
            warn("recomputeAll", t);
        }
    }

    /**
     * The bridge's OWN current per-channel contribution to {@code statId}, summed across the
     * held item, the held tag, and every armor slot (gate decision 35: a DOT branch subtracts
     * this from the attacker fold so per-stack enhancement never buffs a DOT tick, matching the
     * pre-migration behavior where the DOT path never read held metadata at all). Re-derives the
     * sources fresh rather than reading the {@link EntityStatMap} back, so it is accurate even
     * before the first {@link #recomputeAll} apply and respects the same {@link EntryFilter}.
     */
    public double bridgedSum(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
            @Nonnull String statId) {
        try {
            double sum = matchingAmount(heldSourceEntries(store, ref), statId);
            sum += matchingAmount(tagSourceEntries(store, ref), statId);
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

    @Nonnull
    private static Map<String, Double> tagSourceEntries(@Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ref) {
        ItemStack stack = InventoryComponent.getItemInHand(store, ref);
        if (stack == null) {
            return Collections.emptyMap();
        }
        return HeldItemStatsTag.entriesOf(stack);
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
    private String tagKey(int slot) {
        return namespace + ":tag:" + slot;
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
     * active-slot switch (hotbar or utility). A consumer registers a small concrete subclass,
     * e.g. {@code new EquipStatBridge.ActiveSlotTrigger(bridge) {}}, via its own {@code
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
}
