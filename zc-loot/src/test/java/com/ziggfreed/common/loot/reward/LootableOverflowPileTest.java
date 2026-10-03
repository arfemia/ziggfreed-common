package com.ziggfreed.common.loot.reward;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.annotation.Nonnull;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.ziggfreed.common.loot.FactorLookup;
import com.ziggfreed.common.loot.LootEngine;
import com.ziggfreed.common.loot.LootGrants;
import com.ziggfreed.common.loot.Roll;
import com.ziggfreed.common.subject.Subject;
import com.ziggfreed.common.testing.EngineAssetStores;

/**
 * The overflow policy answers for a whole pile, and a rolled table's items reach it through the
 * ground-spill preset: what does not fit the bag goes to the policy as ONE pile, counts as found
 * only when the policy landed it, and is warned, naming the table, when it landed nowhere (a
 * cleared policy included), since a rolled item has no replayable form to park.
 *
 * <p>No live player exists in a unit JVM, so the subject here has none: the bag step is skipped and
 * every stack goes straight to the policy, which is exactly the leg under test. A native drop list
 * cannot be rolled without the engine's item module, so the drop-lists leaf is pinned by the
 * preset's own test through its roll seam.
 *
 * <p>Tagged {@code engine-items}: an {@link ItemStack}, even a stub one, only loads under the
 * engine's log manager. The item sink builds a REAL stack from an id, which asks the item asset
 * store for the item, so those cases swap an empty store into the item class's slot for their
 * length: every id then reads as the engine's unknown item, which is all a stack's constructor needs.
 */
@Tag("engine-items")
class LootableOverflowPileTest {

    private static final String SOURCE = "reward:demo";

    private LootRewardKinds.Overflow saved;

    @BeforeEach
    void savePolicy() {
        saved = LootRewardKinds.installedOverflow();
    }

    @AfterEach
    void restorePolicy() {
        // The policy is process-wide: leave it exactly as this class found it.
        LootRewardKinds.overflow(saved);
    }

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

    private static Subject noPlayer() {
        return Subject.of(UUID.randomUUID(), "tester");
    }

    private static List<String> described(List<ItemStack> stacks) {
        List<String> out = new ArrayList<>();
        for (ItemStack s : stacks) {
            out.add(s.getItemId() + " x" + s.getQuantity());
        }
        return out;
    }

    /** A policy that records every pile it is handed and answers a fixed outcome. */
    private static final class RecordingPolicy implements LootRewardKinds.Overflow {
        final List<List<String>> piles = new ArrayList<>();
        int singles;
        final boolean lands;

        RecordingPolicy(boolean lands) {
            this.lands = lands;
        }

        @Override
        public boolean handle(@Nonnull Subject subject, @Nonnull ItemStack stack) {
            singles++;
            return lands;
        }

        @Override
        public boolean handleAll(@Nonnull Subject subject, @Nonnull List<ItemStack> stacks) {
            piles.add(described(stacks));
            return lands;
        }
    }

    // ==================== the default pile answer ====================

    @Test
    void theDefaultOffersEveryStackInOrderAndAnswersTrueOnlyWhenAllLanded() {
        List<String> offered = new ArrayList<>();
        LootRewardKinds.Overflow policy = (subject, s) -> {
            offered.add(s.getItemId());
            if (s.getItemId().equals("Thrown")) {
                throw new IllegalStateException("boom");
            }
            return !s.getItemId().equals("Refused");
        };

        boolean all = policy.handleAll(noPlayer(),
                List.of(stack("A", 1), stack("Refused", 2), stack("Thrown", 3), stack("D", 4)));

        assertEquals(List.of("A", "Refused", "Thrown", "D"), offered,
                "a refused or throwing stack never stops the ones after it");
        assertFalse(all, "a pile that landed in part is not reported as landed");
    }

    @Test
    void theDefaultAnswersTrueWhenEveryStackLandedAndForAnEmptyPile() {
        List<String> offered = new ArrayList<>();
        LootRewardKinds.Overflow policy = (subject, s) -> offered.add(s.getItemId());

        assertTrue(policy.handleAll(noPlayer(), List.of(stack("A", 1), stack("B", 2))));
        assertEquals(List.of("A", "B"), offered);
        assertTrue(policy.handleAll(noPlayer(), List.of()), "nothing to land is nothing lost");
        assertEquals(List.of("A", "B"), offered, "an empty pile offers nothing");
    }

    @Test
    void theFeetDropWithNoPlayerAnswersFalseForAPile() {
        assertFalse(new FeetDropOverflow().handleAll(noPlayer(), List.of(stack("A", 1), stack("B", 2))),
                "no feet to drop at, so the pile did not land");
    }

    // ==================== a rolled table's items ====================

    private LootEngine.Result rollItem(List<String> warned) {
        RewardSpec spec = RewardSpec.of(LootRewardKinds.KIND_LOOTABLE, Map.of("Lootable", "demo"));
        try (EngineAssetStores.Swap ignored = EngineAssetStores.emptyItems()) {
            return LootEngine.rollAndGrant(
                    List.of(Roll.of(null, null, null, null, LootGrants.ofItem("Coin_Gold", 3), null)),
                    null, FactorLookup.none(), () -> 0.0,
                    LootRewardKinds.lootableSinks(spec, noPlayer(), new RewardKindRegistry(), SOURCE,
                            warned::add));
        }
    }

    @Test
    void anItemPastTheBagReachesThePolicyAsOnePileAndCountsWhenItLands() {
        RecordingPolicy policy = new RecordingPolicy(true);
        LootRewardKinds.overflow(policy);
        List<String> warned = new ArrayList<>();

        LootEngine.Result result = rollItem(warned);

        assertEquals(List.of(List.of("Coin_Gold x3")), policy.piles, "one pile call carrying the one stack");
        assertEquals(0, policy.singles, "the pile is answered whole, never stack by stack");
        assertEquals(Map.of("Coin_Gold", 3), result.getItems(), "a landed pile counts as found");
        assertEquals(List.of(), warned);
    }

    @Test
    void aPileThePolicyCouldNotLandCountsNothingAndIsWarnedWithItsTable() {
        RecordingPolicy policy = new RecordingPolicy(false);
        LootRewardKinds.overflow(policy);
        List<String> warned = new ArrayList<>();

        LootEngine.Result result = rollItem(warned);

        assertEquals(1, policy.piles.size());
        assertTrue(result.getItems().isEmpty(), "a pile that went nowhere is never reported as found");
        assertEquals(1, warned.size(), () -> "one line for the lost pile: " + warned);
        assertTrue(warned.get(0).contains(SOURCE) && warned.get(0).contains("Coin_Gold x3"),
                () -> "the line names the table and the stack: " + warned);
    }

    @Test
    void withThePolicyClearedARolledItemIsLostAndWarnedNotParked() {
        LootRewardKinds.overflow(null);
        List<String> warned = new ArrayList<>();

        LootEngine.Result result = rollItem(warned);

        assertTrue(result.getItems().isEmpty());
        assertEquals(1, warned.size(), () -> "one line for the lost pile: " + warned);
        assertTrue(warned.get(0).contains(SOURCE) && warned.get(0).contains("Coin_Gold x3"),
                () -> "the line names the table and the stack: " + warned);
    }
}
