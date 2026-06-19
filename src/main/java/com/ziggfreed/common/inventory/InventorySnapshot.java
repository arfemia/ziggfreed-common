package com.ziggfreed.common.inventory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.inventory.ActiveSlotInventoryComponent;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * A faithful, slot-exact capture of an entity's full inventory across all six sections
 * (Armor / Hotbar / Storage / Utility / Tool / Backpack), preserving each {@link ItemStack}'s
 * durability + metadata and each active-slot section's selected slot. The reusable engine
 * primitive behind a minigame's "preserve your overworld gear, then restore it on exit" lifecycle.
 *
 * <p>The three operations are deliberately asymmetric:
 * <ul>
 *   <li>{@link #capture} reads the WHOLE inventory (never policy-scoped) - the snapshot is the
 *       authoritative entry state.</li>
 *   <li>{@link #strip} removes items per an {@link InventoryStripPolicy} - the only configurable
 *       step (strip everything, keep a section like armor, or whitelist/blacklist item ids).</li>
 *   <li>{@link #apply} restores to the EXACT entry state: it clears every managed section first
 *       (dropping any loot gained in-round AND any kept-on-entry item), then reapplies the captured
 *       slots. Idempotent, so a retry after a partial failure converges.</li>
 * </ul>
 *
 * <p><b>World thread only</b> (touches the {@link Store}). Each section is independently guarded so
 * one bad section never aborts the rest. Serialize/persist via {@link InventorySnapshotStore}.
 */
public final class InventorySnapshot {

    /** The six managed sections, captured + restored in a fixed order (active-slot sections last is irrelevant). */
    static final int[] SECTION_IDS = {
        InventoryComponent.ARMOR_SECTION_ID,
        InventoryComponent.HOTBAR_SECTION_ID,
        InventoryComponent.STORAGE_SECTION_ID,
        InventoryComponent.UTILITY_SECTION_ID,
        InventoryComponent.TOOLS_SECTION_ID,
        InventoryComponent.BACKPACK_SECTION_ID,
    };

    /** One section's captured slots plus, for an active-slot section, its selected slot ({@code -1} otherwise). */
    public static final class SectionData {
        private final int sectionId;
        private final byte activeSlot;
        private final Map<Short, ItemStack> slots;

        public SectionData(int sectionId, byte activeSlot, @Nonnull Map<Short, ItemStack> slots) {
            this.sectionId = sectionId;
            this.activeSlot = activeSlot;
            this.slots = slots;
        }

        public int sectionId() {
            return sectionId;
        }

        public byte activeSlot() {
            return activeSlot;
        }

        @Nonnull
        public Map<Short, ItemStack> slots() {
            return slots;
        }
    }

    private final List<SectionData> sections;

    public InventorySnapshot(@Nonnull List<SectionData> sections) {
        this.sections = sections;
    }

    @Nonnull
    public List<SectionData> sections() {
        return sections;
    }

    /** Whether the captured inventory held no items at all. */
    public boolean isEmpty() {
        for (SectionData s : sections) {
            if (!s.slots.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    // ==================== capture ====================

    /** Snapshot the entity's WHOLE inventory (every section, every non-empty slot). Does NOT mutate. */
    @Nonnull
    public static InventorySnapshot capture(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref) {
        List<SectionData> out = new ArrayList<>(SECTION_IDS.length);
        for (int sectionId : SECTION_IDS) {
            try {
                InventoryComponent comp = section(store, ref, sectionId);
                if (comp == null) {
                    continue;
                }
                ItemContainer container = comp.getInventory();
                if (container == null) {
                    continue;
                }
                Map<Short, ItemStack> slots = new LinkedHashMap<>();
                short capacity = container.getCapacity();
                for (short slot = 0; slot < capacity; slot++) {
                    ItemStack stack = container.getItemStack(slot);
                    if (!ItemStack.isEmpty(stack)) {
                        slots.put(slot, stack); // ItemStack is immutable; the reference is a safe capture
                    }
                }
                byte active = comp instanceof ActiveSlotInventoryComponent asc ? asc.getActiveSlot() : (byte) -1;
                out.add(new SectionData(sectionId, active, slots));
            } catch (Throwable ignored) {
                // best-effort per section: a bad section never aborts the capture
            }
        }
        return new InventorySnapshot(out);
    }

    // ==================== strip ====================

    /** Remove items from the live inventory per {@code policy} (covered sections + item rule). */
    public static void strip(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
                             @Nonnull InventoryStripPolicy policy) {
        for (int sectionId : SECTION_IDS) {
            if (!policy.coversSection(sectionId)) {
                continue;
            }
            try {
                InventoryComponent comp = section(store, ref, sectionId);
                if (comp == null) {
                    continue;
                }
                ItemContainer container = comp.getInventory();
                if (container == null) {
                    continue;
                }
                if (policy.clearsSection(sectionId)) {
                    container.clear(); // fast path: whole section, one transaction
                    continue;
                }
                short capacity = container.getCapacity();
                for (short slot = 0; slot < capacity; slot++) {
                    ItemStack stack = container.getItemStack(slot);
                    if (!ItemStack.isEmpty(stack) && policy.shouldStrip(stack.getItemId())) {
                        container.removeItemStackFromSlot(slot, false); // filter off: force-remove
                    }
                }
            } catch (Throwable ignored) {
                // best-effort per section
            }
        }
    }

    // ==================== apply (restore to entry) ====================

    /**
     * Restore this snapshot onto the live inventory: clear every managed section (dropping any loot
     * gained in-round and any item kept on entry), then reapply the captured slots + active slots.
     * Idempotent (clear-then-reapply), so it is safe to run twice or to retry after a partial failure.
     */
    public void apply(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref) {
        // 1) Clear every managed section so the result is EXACTLY the captured entry state.
        for (int sectionId : SECTION_IDS) {
            try {
                InventoryComponent comp = section(store, ref, sectionId);
                if (comp != null) {
                    ItemContainer container = comp.getInventory();
                    if (container != null) {
                        container.clear();
                    }
                }
            } catch (Throwable ignored) {
                // best-effort per section
            }
        }
        // 2) Reapply each captured section's slots exactly (filter off: the items came from here),
        //    then restore the active slot.
        for (SectionData sd : sections) {
            try {
                InventoryComponent comp = section(store, ref, sd.sectionId);
                if (comp == null) {
                    continue;
                }
                ItemContainer container = comp.getInventory();
                if (container == null) {
                    continue;
                }
                short capacity = container.getCapacity();
                for (Map.Entry<Short, ItemStack> e : sd.slots.entrySet()) {
                    short slot = e.getKey();
                    if (slot >= 0 && slot < capacity) {
                        container.setItemStackForSlot(slot, e.getValue(), false);
                    }
                }
                if (sd.activeSlot >= 0 && comp instanceof ActiveSlotInventoryComponent asc) {
                    asc.setActiveSlot(sd.activeSlot, ref, store);
                }
            } catch (Throwable ignored) {
                // best-effort per section: a bad slot never aborts the rest of the restore
            }
        }
    }

    @Nullable
    private static InventoryComponent section(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
                                              int sectionId) {
        ComponentType<EntityStore, ? extends InventoryComponent> type = InventoryComponent.getComponentTypeById(sectionId);
        if (type == null) {
            return null;
        }
        return store.getComponent(ref, type);
    }
}
