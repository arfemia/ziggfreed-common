package com.ziggfreed.common.loot.reward;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiPredicate;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.ziggfreed.common.registry.RegistryLedger;
import com.ziggfreed.common.subject.Subject;
import com.ziggfreed.common.testing.EngineAssetStores;

/**
 * A {@code Command} reward whose line is a {@code give} hands over an ITEM, so the fit probe has to
 * see it.
 *
 * <p>The failure this closes is the one the batch exists to prevent, one level down: a hand-in
 * spends its completion because the probe reported room, and the command's item then lands on the
 * floor because nothing ever asked about it. What is pinned here is the part that decides WHETHER a
 * command needs room at all, which is settled from the line alone - a spec with nothing to hand over
 * answers null and is skipped, exactly like a rolling kind. Whether a stack that DOES need room
 * fits is {@code GroundSpillSinks.pickupFits}' answer about a live player, and lands with the
 * in-game pass.
 */
class CommandRewardFitTest {

    private Subject player;

    @BeforeEach
    void setUp() {
        player = Subject.of(UUID.randomUUID(), "tester");
    }

    private RewardSpec command(String line) {
        return RewardSpec.of(LootRewardKinds.KIND_COMMAND, LootRewardKinds.P_COMMAND, line);
    }

    @Test
    void aCommandThatHandsOverNoItemNeedsNoRoom() {
        assertNull(LootRewardKinds.stackFor(command("say well done {player}"), player, "quest:demo"));
        assertNull(LootRewardKinds.stackFor(command("tp {player} 0 64 0"), player, "quest:demo"));
    }

    @Test
    void aCommandRewardThatNamesNoCommandIsReadAsNeedingNoRoomRatherThanThrowing() {
        // Refusing here would report a full bag for what is really an authoring mistake, and hide
        // the mistake behind a message about inventory space. The grant path still fails loudly.
        assertNull(LootRewardKinds.stackFor(RewardSpec.of(LootRewardKinds.KIND_COMMAND), player, "quest:demo"));
    }

    @Test
    void anotherModsKindIsStillNoneOfThisProbesBusiness() {
        assertNull(LootRewardKinds.stackFor(RewardSpec.of("Mmo_Xp", "skill", "MINING"), player, "quest:demo"));
    }

    @Test
    void aListOfRewardsThatNeedNoRoomFitsWithNobodyToHandThemTo() {
        assertTrue(LootRewardKinds.canAddAll(
                List.of(command("say hello"), RewardSpec.of("Mmo_Xp", "skill", "MINING")),
                player, "quest:demo"));
    }

    @Test
    void theSourceLabelReachesTheCommandLineTheProbeReads() {
        // {source} substitutes exactly as it will at grant time, so a line whose ITEM is named by
        // the source cannot read one way in the probe and another way in the payout. This one names
        // no item at all either way, which is what makes it assertable with no engine underneath.
        assertNull(LootRewardKinds.stackFor(command("say finished {source}"), player, "quest:demo"));
    }

    @Test
    @Tag("engine-items")
    void aKindFileWhoseHandsNamesAnItemWaitsForRoomInTheBag() {
        // A kind file's command line is opaque, so its Hands is the one way the batch learns an item
        // is coming: before it, a claim with a full bag went through and the item landed at the
        // player's feet. The kind goes in through the fold, as a server's kind files do, and a bag
        // stands in for the player's inventory, which a unit JVM has none of. Tagged engine-items:
        // the probe builds a real stack, against an empty item store.
        Runnable restoreShared = snapshotOf(RewardKinds.shared());
        try {
            RewardKindFold.foldInto(RewardKinds.shared(), List.of(RewardKindAsset.of("Test_Hands_Kind",
                    Map.of("Amount", RewardKindAsset.Param.of(true, null)), "testhands give {player} {Amount}",
                    null, RewardKindAsset.Hands.of("Test_Hands_Item", 2))), line -> true, null);
            List<RewardSpec> claim = List.of(RewardSpec.of("Test_Hands_Kind", "Amount", "750"));
            List<String> asked = new ArrayList<>();

            try (EngineAssetStores.Swap ignored = EngineAssetStores.emptyItems()) {
                assertFalse(LootRewardKinds.canAddAll(claim, player, "quest:demo", bagWithFreeSlots(0, asked)),
                        "a full bag refuses the claim");
                assertTrue(LootRewardKinds.canAddAll(claim, player, "quest:demo", bagWithFreeSlots(36, asked)),
                        "an empty bag takes it");
            }
            assertEquals(List.of("Test_Hands_Item x2", "Test_Hands_Item x2"), asked,
                    "each probe asked the bag about Count of the item Hands names, in one batch");
        } finally {
            // The shared vocabulary is process-wide: take the test's kind out again by putting the
            // registry back exactly as this test found it.
            restoreShared.run();
        }
    }

    /**
     * What {@code kinds} holds now, as a step that puts it back. The registry has no per-id removal,
     * only {@code clear}, so the step clears it and registers again every kind it held (with its
     * owner) and every authoring token; a kind's failure count is not carried over.
     */
    private static Runnable snapshotOf(RewardKindRegistry kinds) {
        Map<String, RegistryLedger.RegistrationInfo> info = kinds.info();
        Map<String, RewardHandler> handlers = new LinkedHashMap<>();
        for (String id : kinds.ids()) {
            handlers.put(id, kinds.handler(id));
        }
        Map<String, RewardAuthoring> authoring = new LinkedHashMap<>();
        for (String token : kinds.authoringTokens()) {
            authoring.put(token, kinds.authoring(token));
        }
        return () -> {
            kinds.clear();
            handlers.forEach((id, handler) -> {
                RegistryLedger.RegistrationInfo registration = info.get(id);
                kinds.registerQuietly(id, registration == null ? null : registration.owner(), handler);
            });
            authoring.forEach(kinds::registerAuthoring);
        };
    }

    /** A bag with {@code free} empty slots, one stack to a slot, recording every stack it is asked about. */
    private static BiPredicate<Subject, List<ItemStack>> bagWithFreeSlots(int free, List<String> asked) {
        return (who, stacks) -> {
            for (ItemStack stack : stacks) {
                asked.add(stack.getItemId() + " x" + stack.getQuantity());
            }
            return stacks.size() <= free;
        };
    }
}
