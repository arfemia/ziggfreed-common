package com.ziggfreed.common.loot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.server.core.inventory.ItemStack;

/**
 * The ground-spill preset with its inventory, its ground and its drop-list roll replaced by fixtures:
 * inventory first, the rest in ONE pile per hand-over, and a landed count that reports only what
 * reached the player unless the site asks to count a refused pile too.
 *
 * <p>Tagged {@code engine-items}: an {@link ItemStack}, even a stub one, only loads under the engine's
 * log manager. The stubs set the two fields the preset reads and never touch the item asset store,
 * which is also why the item sink (it builds a real stack from an id) is covered through
 * {@link GroundSpillSinks#spill}, the hand-over it delegates to.
 */
@Tag("engine-items")
class GroundSpillSinksTest {

    // The parameter is not named after the field: inside the stub's initializer it would read the
    // inherited field, not the argument.
    private static ItemStack stack(String id, int count) {
        return new ItemStack() {
            {
                this.itemId = id;
                this.quantity = count;
            }
        };
    }

    /** A ground that records every pile it was handed and answers a fixed outcome. */
    private static final class RecordingGround implements GroundSpillSinks.Ground {
        final List<List<String>> piles = new ArrayList<>();
        boolean lands = true;

        @Override
        public boolean drop(List<ItemStack> stacks) {
            List<String> pile = new ArrayList<>();
            for (ItemStack s : stacks) {
                pile.add(s.getItemId() + " x" + s.getQuantity());
            }
            piles.add(pile);
            return lands;
        }
    }

    @Test
    void whatFitsGoesInTheBagAndTheRestLandsAsOnePile() {
        RecordingGround ground = new RecordingGround();
        GroundSpillSinks spill = GroundSpillSinks.at(ground)
                .inventory(s -> s.getItemId().equals("Fits"))
                .build();

        Map<String, Integer> landed = spill.spill(List.of(stack("Fits", 2), stack("Big", 3), stack("Big", 1),
                stack("Other", 4)));

        assertEquals(List.of(List.of("Big x3", "Big x1", "Other x4")), ground.piles,
                "every leftover in ONE drop call, in hand-over order");
        assertEquals(Map.of("Fits", 2, "Big", 4, "Other", 4), landed);
        assertEquals(List.of("Fits", "Big", "Other"), new ArrayList<>(landed.keySet()),
                "the bag's stacks report first, then the pile's");
    }

    @Test
    void withNoInventoryEverythingSpills() {
        RecordingGround ground = new RecordingGround();
        GroundSpillSinks spill = GroundSpillSinks.at(ground).build();

        assertEquals(Map.of("A", 1, "B", 2), spill.spill(List.of(stack("A", 1), stack("B", 2))));
        assertEquals(1, ground.piles.size());
    }

    @Test
    void aPileThatDidNotLandIsNotReportedUnlessTheSiteCountsFailedDrops() {
        RecordingGround ground = new RecordingGround();
        ground.lands = false;

        Map<String, Integer> strict = GroundSpillSinks.at(ground)
                .inventory(s -> s.getItemId().equals("Fits"))
                .build()
                .spill(List.of(stack("Fits", 1), stack("Lost", 5)));
        assertEquals(Map.of("Fits", 1), strict, "the bag's stack landed; the refused pile did not");

        Map<String, Integer> counted = GroundSpillSinks.at(ground)
                .countFailedDrops(true)
                .build()
                .spill(List.of(stack("Lost", 5)));
        assertEquals(Map.of("Lost", 5), counted);
    }

    @Test
    void aGroundThatThrowsIsWarnedAndReadAsNotLanded() {
        List<String> warned = new ArrayList<>();
        GroundSpillSinks spill = GroundSpillSinks.at(stacks -> {
            throw new IllegalStateException("boom");
        }).warn(warned::add).build();

        assertTrue(spill.spill(List.of(stack("A", 1))).isEmpty());
        assertEquals(1, warned.size());
    }

    @Test
    void anEmptyHandOverNeverTouchesTheGround() {
        RecordingGround ground = new RecordingGround();
        GroundSpillSinks spill = GroundSpillSinks.at(ground).inventory(s -> true).build();

        assertEquals(Map.of("A", 1), spill.spill(List.of(stack("A", 1))));
        assertTrue(spill.spill(new ArrayList<>()).isEmpty());
        assertTrue(ground.piles.isEmpty(), "nothing overflowed, so no drop call at all");
    }

    @Test
    void aDropListRollsOnceAndSpillsByTheSameRulesThroughTheEngine() {
        RecordingGround ground = new RecordingGround();
        List<String> rolled = new ArrayList<>();
        GroundSpillSinks spill = GroundSpillSinks.at(ground)
                .inventory(s -> s.getItemId().equals("Fits"))
                .roller(id -> {
                    rolled.add(id);
                    return id.equals("Empty") ? List.of() : List.of(stack("Fits", 1), stack("Spill", 2));
                })
                .build();

        LootEngine.Result result = LootEngine.rollAndGrant(
                List.of(Roll.of(null, null, null, null, LootGrants.ofDropList("Table"), null),
                        Roll.of(null, null, null, null, LootGrants.ofDropList("Empty"), "never")),
                null, FactorLookup.none(), () -> 0.0, spill.into(LootEngine.Sinks.builder()).build());

        assertEquals(List.of("Table", "Empty"), rolled);
        assertEquals(List.of(List.of("Spill x2")), ground.piles, "one pile for the one roll that overflowed");
        assertEquals(Map.of("Fits", 1, "Spill", 2), result.getItems());
        assertTrue(result.getCues().isEmpty(), "an empty roll produced nothing, so its cue stays silent");
    }
}
