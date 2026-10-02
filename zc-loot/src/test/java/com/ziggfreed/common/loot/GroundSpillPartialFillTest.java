package com.ziggfreed.common.loot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.transaction.ActionType;
import com.hypixel.hytale.server.core.inventory.transaction.ItemStackTransaction;

/**
 * The ground-spill preset with an inventory step that can take PART of a stack: what went in counts
 * as landed, and only the part left over joins the one ground pile. The released whole-or-nothing
 * step keeps its meaning through the interface's default, and the engine give's answer is read as
 * the engine writes it.
 *
 * <p>Tagged {@code engine-items}: an {@link ItemStack}, even a stub one, only loads under the
 * engine's log manager. The stubs set the two fields the preset reads.
 */
@Tag("engine-items")
class GroundSpillPartialFillTest {

    private static ItemStack stack(String id, int count) {
        return new ItemStack() {
            {
                this.itemId = id;
                this.quantity = count;
            }
        };
    }

    /** An inventory with room for {@code room} more items of any kind, answering what did not fit. */
    private static final class RoomFor implements GroundSpillSinks.Inventory {
        int room;
        final List<ItemStack> offered = new ArrayList<>();

        RoomFor(int room) {
            this.room = room;
        }

        @Override
        public boolean accept(@Nonnull ItemStack stack) {
            throw new AssertionError("a step that overrides offer is offered, never asked to accept");
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

    /** A ground that records every pile it was handed. */
    private static final class RecordingGround implements GroundSpillSinks.Ground {
        final List<List<String>> piles = new ArrayList<>();

        @Override
        public boolean drop(List<ItemStack> stacks) {
            List<String> pile = new ArrayList<>();
            for (ItemStack s : stacks) {
                pile.add(s.getItemId() + " x" + s.getQuantity());
            }
            piles.add(pile);
            return true;
        }
    }

    @Test
    void whenPartFitsThePartLandsAndOnlyTheRemainderJoinsTheOnePile() {
        RecordingGround ground = new RecordingGround();
        RoomFor bag = new RoomFor(5);
        GroundSpillSinks spill = GroundSpillSinks.at(ground).inventory(bag).build();

        Map<String, Integer> landed = spill.spill(List.of(stack("Fruit", 3), stack("Fruit", 4), stack("Seed", 2)));

        assertEquals(List.of(List.of("Fruit x2", "Seed x2")), ground.piles,
                "the remainder of the partly-fitted stack and the stack that did not fit, in ONE pile");
        assertEquals(Map.of("Fruit", 7, "Seed", 2), landed, "three plus two went in, and the pile landed");
        assertEquals(3, bag.offered.size(), "every stack was offered");
    }

    @Test
    void whenNothingFitsTheWholeStackIsThePile() {
        RecordingGround ground = new RecordingGround();
        ItemStack fruit = stack("Fruit", 3);
        GroundSpillSinks.Ground keep = stacks -> {
            assertSame(fruit, stacks.get(0), "the stack itself goes to the ground, never a rebuilt one");
            return ground.drop(stacks);
        };

        Map<String, Integer> landed = GroundSpillSinks.at(keep).inventory(new RoomFor(0)).build()
                .spill(List.of(fruit));

        assertEquals(List.of(List.of("Fruit x3")), ground.piles);
        assertEquals(Map.of("Fruit", 3), landed);
    }

    @Test
    void whenAllFitsNothingTouchesTheGround() {
        RecordingGround ground = new RecordingGround();

        Map<String, Integer> landed = GroundSpillSinks.at(ground).inventory(new RoomFor(10)).build()
                .spill(List.of(stack("Fruit", 3), stack("Seed", 2)));

        assertEquals(Map.of("Fruit", 3, "Seed", 2), landed);
        assertTrue(ground.piles.isEmpty());
    }

    @Test
    void theDefaultOfferIsWholeOrNothingThroughAccept() {
        ItemStack fruit = stack("Fruit", 3);
        GroundSpillSinks.Inventory yes = s -> true;
        GroundSpillSinks.Inventory no = s -> false;

        assertNull(yes.offer(fruit), "accepted whole: nothing left over");
        assertSame(fruit, no.offer(fruit), "refused: the whole stack is left over");
    }

    @Test
    void theEngineGivesAnswerIsReadAsItsRemainderOrTheWholeStackOnAFailureNamingNone() {
        ItemStack fruit = stack("Fruit", 3);
        ItemStack two = stack("Fruit", 2);

        assertSame(fruit, GroundSpillSinks.remainderOf(null, fruit));
        assertSame(fruit, GroundSpillSinks.remainderOf(ItemStackTransaction.FAILED_ADD, fruit),
                "the engine's shared failed add names no remainder, and nothing went in");
        assertNull(GroundSpillSinks.remainderOf(new ItemStackTransaction(true, ActionType.ADD, fruit, null, false,
                false, List.of()), fruit), "all of it went in");
        assertSame(two, GroundSpillSinks.remainderOf(new ItemStackTransaction(true, ActionType.ADD, fruit, two, false,
                false, List.of()), fruit), "part went in, the rest is the remainder");
    }
}
