package com.ziggfreed.common.loot.trigger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.DoubleSupplier;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.server.core.event.events.ecs.InteractivelyPickupItemEvent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.ziggfreed.common.loot.FactorLookup;
import com.ziggfreed.common.loot.GroundSpillSinks;
import com.ziggfreed.common.loot.LootEngine;
import com.ziggfreed.common.loot.LootGrants;
import com.ziggfreed.common.loot.Roll;
import com.ziggfreed.common.loot.reward.MomentItems;
import com.ziggfreed.common.loot.reward.RewardKinds;
import com.ziggfreed.common.subject.Subject;
import com.ziggfreed.common.testing.EngineAssetStores;

/**
 * One bonus pass once its chance has fired, and the harvest a deferred harvest pass starts from.
 * Driven with a fixture inventory step, a recording ground and stub stacks, so what is pinned is the
 * mechanics: what each pass carries, where its items land, and what counts as landed.
 *
 * <p>Tagged {@code engine-items}: an {@link ItemStack}, even a stub one, only loads under the
 * engine's log manager. The harvest stub overrides {@code cleanCopy} so a copy is told apart from
 * the harvest without the item store a real copy would ask for.
 */
@Tag("engine-items")
class BonusPassesTest {

    private final List<String> warnings = new ArrayList<>();

    @BeforeEach
    void kinds() {
        MomentItems.registerInto(RewardKinds.shared());
    }

    /** A stub harvest whose engine copy is a fresh stub of the same item and quantity, recorded. */
    private static final class Harvest extends ItemStack {

        final List<ItemStack> copies = new ArrayList<>();

        Harvest(String id, int count) {
            this.itemId = id;
            this.quantity = count;
        }

        @Override
        @Nonnull
        public ItemStack cleanCopy() {
            Harvest copy = new Harvest(getItemId(), getQuantity());
            copies.add(copy);
            return copy;
        }
    }

    private static ItemStack stack(String id, int count) {
        return new ItemStack() {
            {
                this.itemId = id;
                this.quantity = count;
            }
        };
    }

    /** An inventory step with room for {@code room} more items, answering the part that did not fit. */
    private static final class Bag implements GroundSpillSinks.Inventory {
        int room;
        final List<ItemStack> offered = new ArrayList<>();

        Bag(int room) {
            this.room = room;
        }

        @Override
        public boolean accept(@Nonnull ItemStack stack) {
            throw new AssertionError("a step that fills part of a stack is offered, never asked to accept");
        }

        @Override
        @Nullable
        public ItemStack offer(@Nonnull ItemStack stack) {
            offered.add(stack);
            int took = Math.min(room, stack.getQuantity());
            room -= took;
            if (took == stack.getQuantity()) {
                return null;
            }
            return took == 0 ? stack : stack(stack.getItemId(), stack.getQuantity() - took);
        }
    }

    /** A ground that records every pile it was handed and answers {@code lands} for each. */
    private static final class Ground implements GroundSpillSinks.Ground {
        final boolean lands;
        final List<List<String>> piles = new ArrayList<>();

        Ground(boolean lands) {
            this.lands = lands;
        }

        @Override
        public boolean drop(@Nonnull List<ItemStack> stacks) {
            List<String> pile = new ArrayList<>();
            for (ItemStack s : stacks) {
                pile.add(s.getItemId() + " x" + s.getQuantity());
            }
            piles.add(pile);
            return lands;
        }
    }

    private static LootEngine.Resolved loot(Roll... rolls) {
        return new LootEngine.Resolved(List.of(rolls), List.of());
    }

    /** One roll granting a {@code Moment_Item} reward per count (null for an unwritten Count). */
    private static Roll momentItems(@Nullable String... counts) {
        LootGrants.Reward[] rewards = new LootGrants.Reward[counts.length];
        for (int i = 0; i < counts.length; i++) {
            rewards[i] = LootGrants.Reward.of(MomentItems.KIND, counts[i] == null ? null : Map.of("Count", counts[i]));
        }
        return Roll.of(null, null, null, null, LootGrants.of(null, null, null, rewards), null);
    }

    private static Roll items(String itemId, int count) {
        return Roll.of(null, null, null, null, LootGrants.ofItem(itemId, count), null);
    }

    private static Subject tester() {
        return Subject.of(UUID.randomUUID(), "tester");
    }

    private BonusPasses.Outcome harvestPass(Bag bag, Ground ground, Harvest harvest, DoubleSupplier sample,
            Roll... rolls) {
        return BonusPasses.run(GroundSpillSinks.at(ground).inventory(bag), loot(rolls), FactorLookup.none(),
                sample, harvest, tester(), null, Map.of(), "bonusrow:fixture_harvest", warnings::add);
    }

    // ==================== the harvest pass ====================

    @Test
    void aHarvestPassHandsOverOneSeparateCopyOfferedToTheInventoryFirst() {
        Harvest harvest = new Harvest("Plant_Crop_Pumpkin_Item", 3);
        Bag bag = new Bag(10);
        Ground ground = new Ground(true);

        BonusPasses.Outcome pass = harvestPass(bag, ground, harvest, () -> 0.0, momentItems((String) null));

        assertEquals(1, harvest.copies.size(), "exactly one engine copy");
        assertSame(harvest.copies.get(0), bag.offered.get(0), "the copy is what the inventory was offered");
        assertNotSame(harvest, bag.offered.get(0), "never the harvest stack itself");
        assertTrue(ground.piles.isEmpty(), "all of it fit");
        assertEquals(3, pass.landed());
    }

    @Test
    void aPartThatDoesNotFitLandsAsOnePile() {
        Harvest harvest = new Harvest("Plant_Crop_Pumpkin_Item", 3);
        Bag bag = new Bag(4);
        Ground ground = new Ground(true);

        BonusPasses.Outcome pass = harvestPass(bag, ground, harvest, () -> 0.0, momentItems("3"));

        assertEquals(List.of(List.of("Plant_Crop_Pumpkin_Item x2", "Plant_Crop_Pumpkin_Item x3")), ground.piles,
                "the rest of the second copy and the whole third copy, in ONE pile");
        assertEquals(9, pass.landed(), "four went in and the pile of five landed");
    }

    @Test
    void twoHalfRewardsGiveOneCopyWithNoDraw() {
        Harvest harvest = new Harvest("Plant_Crop_Pumpkin_Item", 3);
        AtomicInteger draws = new AtomicInteger();

        BonusPasses.Outcome pass = harvestPass(new Bag(100), new Ground(true), harvest, () -> {
            draws.incrementAndGet();
            return 0.99;
        }, momentItems("0.5", "0.5"));

        assertEquals(1, harvest.copies.size(), "summed across the pass first, then resolved once");
        assertEquals(0, draws.get(), "a whole tally consumes no draw");
        assertEquals(3, pass.landed());
    }

    // ==================== a pass with no moment stack ====================

    @Test
    void aPassHandedNoMomentCarriesNoCopySoAMomentItemCountsLost() {
        Ground ground = new Ground(true);

        BonusPasses.Outcome pass = BonusPasses.run(GroundSpillSinks.at(ground), loot(momentItems((String) null)),
                FactorLookup.none(), () -> 0.0, null, tester(), null, Map.of(), "bonusrow:fixture_break",
                warnings::add);

        assertEquals(1, pass.result().getRewardsLost(), "no collector rides a break's or a kill's pass");
        assertEquals(0, pass.landed());
        assertTrue(ground.piles.isEmpty(), "nothing reached the ground");
    }

    @Test
    void aBlockLandingCountsARefusedPileAsFoundAndTheDefaultDoesNot() throws Exception {
        Ground blockGround = new Ground(false);
        Ground corpseGround = new Ground(false);
        BonusPasses.Outcome block;
        BonusPasses.Outcome corpse;
        try (EngineAssetStores.Swap ignored = EngineAssetStores.emptyItems()) {
            block = BonusPasses.run(BonusPasses.blockLanding(blockGround), loot(items("Fixture_Gem", 2)),
                    FactorLookup.none(), () -> 0.0, null, tester(), null, Map.of(), "bonusrow:fixture_block",
                    warnings::add);
            corpse = BonusPasses.run(GroundSpillSinks.at(corpseGround), loot(items("Fixture_Gem", 2)),
                    FactorLookup.none(), () -> 0.0, null, tester(), null, Map.of(), "bonusrow:fixture_corpse",
                    warnings::add);
        }

        assertEquals(1, blockGround.piles.size());
        assertEquals(2, block.landed(), "a broken block's spill counts as found whatever the ground answered");
        assertEquals(1, corpseGround.piles.size());
        assertEquals(0, corpse.landed(), "anywhere else a refused pile is not found");
    }

    // ==================== the harvest read ====================

    @Test
    void aCancelledPickupHasNoHarvest() {
        InteractivelyPickupItemEvent event = new InteractivelyPickupItemEvent(new Harvest("Plant_Crop_Pumpkin_Item", 3));
        event.setCancelled(true);

        assertNull(BonusPasses.harvestOf(event));
    }

    @Test
    void anEmptyStackIsNoHarvest() {
        assertNull(BonusPasses.harvestOf(new InteractivelyPickupItemEvent(ItemStack.EMPTY)));
        assertNull(BonusPasses.harvestOf(new InteractivelyPickupItemEvent(stack("Plant_Crop_Pumpkin_Item", 0))));
    }

    @Test
    void theHarvestIsTheEventsFinalStack() {
        Harvest first = new Harvest("Plant_Crop_Pumpkin_Item", 3);
        Harvest swapped = new Harvest("Plant_Crop_Corn_Item", 1);
        InteractivelyPickupItemEvent event = new InteractivelyPickupItemEvent(first);

        assertSame(first, BonusPasses.harvestOf(event));

        event.setItemStack(swapped);
        assertSame(swapped, BonusPasses.harvestOf(event), "the stack the engine re-reads before it gives");
    }
}
