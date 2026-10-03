package com.ziggfreed.common.encounter.payout;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.ziggfreed.common.loot.GroundSpillSinks;
import com.ziggfreed.common.loot.LootEngine;
import com.ziggfreed.common.loot.LootGrants;
import com.ziggfreed.common.testing.EngineAssetStores;

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
    void anItemGrantThroughThePhaseSinksLandsInThePile() {
        List<ItemStack> pile = new ArrayList<>();
        List<String> warned = new ArrayList<>();
        LootGrants first = LootGrants.of(
                new LootGrants.Item[] {LootGrants.Item.of("Coin_Gold", 3), LootGrants.Item.of("Bone", null)},
                null, null, null);
        LootGrants second = LootGrants.ofItem("Coin_Gold", 2);

        LootEngine.Result result;
        try (EngineAssetStores.Swap ignored = EngineAssetStores.emptyItems()) {
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
}
