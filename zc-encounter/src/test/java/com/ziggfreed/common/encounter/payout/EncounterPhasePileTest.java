package com.ziggfreed.common.encounter.payout;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nonnull;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.assetstore.AssetStore;
import com.hypixel.hytale.assetstore.AssetUpdateQuery;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.event.EventBus;
import com.hypixel.hytale.event.IEventBus;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.ziggfreed.common.loot.GroundSpillSinks;
import com.ziggfreed.common.loot.LootEngine;
import com.ziggfreed.common.loot.LootGrants;

/**
 * A phase drop's ground gathers every hand-over into the ONE pile the phase spawns at the subject
 * once it has rolled everything, in hand-over order, and counts each stack as landed: what a phase
 * drop put on the ground before it went through the shared sinks. An item grant handed over through
 * the phase drop's own sinks lands in that pile.
 *
 * <p>Tagged {@code engine-items}: an {@link ItemStack}, even a stub one, only loads under the
 * engine's log manager. The stubs set the two fields the preset reads and never touch the item
 * asset store. The item sink builds a REAL stack from an id, which asks the item asset store for the
 * item, so that test swaps an empty store into the item class's slot for its length: every id then
 * reads as the engine's unknown item, which is all a stack's constructor needs. (A drop list rolls
 * through the engine's native roll, which a unit JVM has no item module for, so the phase sinks'
 * drop-list leaf is covered by the preset's own test, through its roll seam.)
 */
@Tag("engine-items")
class EncounterPhasePileTest {

    private static ItemStack stack(String id, int count) {
        return new ItemStack() {
            {
                this.itemId = id;
                this.quantity = count;
            }
        };
    }

    @Test
    void anItemGrantThroughThePhaseSinksLandsInThePile() throws ReflectiveOperationException {
        List<ItemStack> pile = new ArrayList<>();
        List<String> warned = new ArrayList<>();
        LootGrants first = LootGrants.of(
                new LootGrants.Item[] {LootGrants.Item.of("Coin_Gold", 3), LootGrants.Item.of("Bone", null)},
                null, null, null);
        LootGrants second = LootGrants.ofItem("Coin_Gold", 2);

        LootEngine.Result result;
        try (EmptyItemStore ignored = EmptyItemStore.install()) {
            result = EncounterLoot.handOverPhase(
                    List.of(new LootEngine.Selected(first, null), new LootEngine.Selected(second, null)),
                    EncounterLoot.phaseSinks(pile, warned::add, line -> true, "Zc_Encounter_Test", "Enraged",
                            "ab12cd34"),
                    () -> { });
        }

        assertEquals(List.of(), warned);
        assertEquals(List.of("Coin_Gold x3", "Bone x1", "Coin_Gold x2"), piled(pile),
                "every item grant joins the one pile, at its count, in payout order");
        assertEquals(Map.of("Coin_Gold", 5, "Bone", 1), result.getItems(), "each counted as landed");
    }

    @Test
    void everyHandOverJoinsOnePileAndCountsAsLanded() {
        List<ItemStack> pile = new ArrayList<>();
        GroundSpillSinks spill = GroundSpillSinks.at(EncounterLoot.gatherInto(pile)).build();

        Map<String, Integer> first = spill.spill(List.of(stack("Coin_Gold", 3), stack("Bone", 1)));
        Map<String, Integer> second = spill.spill(List.of(stack("Coin_Gold", 2)));

        assertEquals(Map.of("Coin_Gold", 3, "Bone", 1), first);
        assertEquals(Map.of("Coin_Gold", 2), second);
        assertEquals(List.of("Coin_Gold x3", "Bone x1", "Coin_Gold x2"), piled(pile),
                "the stacks wait in one pile, unmerged and in order, for the phase's single spawn");
    }

    private static List<String> piled(List<ItemStack> pile) {
        List<String> out = new ArrayList<>();
        for (ItemStack s : pile) {
            out.add(s.getItemId() + " x" + s.getQuantity());
        }
        return out;
    }

    /**
     * An item asset store with nothing in it, swapped into {@code Item}'s private static slot and put
     * back on {@link #close()}: no event listeners, no file monitoring, nothing registered process-wide.
     */
    private static final class EmptyItemStore extends AssetStore<String, Item, DefaultAssetMap<String, Item>>
            implements AutoCloseable {

        private final IEventBus eventBus = new EventBus(false);
        private Field slot;
        private Object original;

        private EmptyItemStore(@Nonnull Builder builder) {
            super(builder);
        }

        static EmptyItemStore install() throws ReflectiveOperationException {
            EmptyItemStore store = new Builder().build();
            store.slot = Item.class.getDeclaredField("ASSET_STORE");
            store.slot.setAccessible(true);
            store.original = store.slot.get(null);
            store.slot.set(null, store);
            return store;
        }

        @Override
        public void close() {
            try {
                slot.set(null, original);
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
        }

        @Nonnull
        @Override
        protected IEventBus getEventBus() {
            return eventBus;
        }

        @Override
        public void addFileMonitor(@Nonnull String packKey, Path path) {
        }

        @Override
        public void removeFileMonitor(Path path) {
        }

        @Override
        protected void handleRemoveOrUpdate(Set<String> toBeRemoved, Map<String, Item> toBeUpdated,
                @Nonnull AssetUpdateQuery query) {
        }

        /** The engine's builder is a protected member of the store, so it is reached from inside one. */
        private static final class Builder
                extends AssetStore.Builder<String, Item, DefaultAssetMap<String, Item>, Builder> {

            Builder() {
                super(String.class, Item.class, new DefaultAssetMap<>());
                setPath("TestEmptyItems");
                setReplaceOnRemove(id -> null);
            }

            @Nonnull
            @Override
            public EmptyItemStore build() {
                return new EmptyItemStore(this);
            }
        }
    }
}
