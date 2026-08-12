package com.ziggfreed.common.loot.reward;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.annotation.Nonnull;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ziggfreed.common.subject.Subject;

/**
 * A reward that cannot name what it pays out must FAIL, never report itself paid.
 *
 * <p>The distinction is the whole safety net. A payout site charges its price, spends a completion
 * or sets a once-flag FIRST and then reads the grant outcome to decide whether to undo any of it; a
 * handler that returns quietly on a missing parameter is counted as delivered, so the price stays
 * spent, no refund runs, the player is told they were paid, and nothing anywhere records that the
 * reward was empty. Failing instead puts the reward in front of both the retry queue and the server
 * owner's log, naming the parameter at fault.
 *
 * <p>Every case here is decided before the handler needs a live player, which is exactly why they
 * are testable: a handle-less {@link Subject} is enough to prove the guard fires first.
 */
class FrameworkKindFailLoudTest {

    private Subject player;
    private RewardKindRegistry kinds;
    private List<String> queued;
    private List<String> warnings;

    @BeforeEach
    void setUp() {
        player = Subject.of(UUID.randomUUID(), "tester");
        kinds = new RewardKindRegistry();
        LootRewardKinds.registerInto(kinds);
        DroplistRewardKind.registerInto(kinds);
        queued = new ArrayList<>();
        warnings = new ArrayList<>();
    }

    private RewardGrants.GrantOutcome grant(@Nonnull RewardSpec spec) {
        return RewardGrants.grantAll(List.of(spec), player, "quest:demo", kinds,
                (subject, command) -> queued.add(command), warnings::add);
    }

    @Test
    void anItemRewardThatNamesNoItemIsNotCountedAsPaid() {
        RewardGrants.GrantOutcome outcome = grant(RewardSpec.of(LootRewardKinds.KIND_ITEM, "count", "5"));

        assertEquals(0, outcome.granted());
        assertFalse(outcome.anyDelivered(), "nothing was delivered, so nothing may claim it was");
        assertEquals(1, outcome.failed(), "no replayable form exists without an item id");
        assertTrue(warnings.stream().anyMatch(w -> w.contains("Item")),
                "the warning must name the parameter at fault, got: " + warnings);
    }

    @Test
    void anItemRewardOfZeroHandsOverNothingAndSaysSo() {
        RewardGrants.GrantOutcome outcome = grant(RewardSpec.of(LootRewardKinds.KIND_ITEM,
                Map.of("item", "Coin_Gold", "count", "0")));

        assertFalse(outcome.anyDelivered());
        assertTrue(warnings.stream().anyMatch(w -> w.contains("Count")), "warnings: " + warnings);
    }

    @Test
    void aStampedItemRewardThatNamesNoItemIsNotCountedAsPaid() {
        RewardGrants.GrantOutcome outcome = grant(
                RewardSpec.of(LootRewardKinds.KIND_STAMPED_ITEM, "stats", "Damage:5"));

        assertEquals(0, outcome.granted());
        assertFalse(outcome.anyDelivered());
    }

    @Test
    void aLootableRewardThatNamesNoTableIsNotCountedAsPaid() {
        RewardGrants.GrantOutcome outcome = grant(RewardSpec.of(LootRewardKinds.KIND_LOOTABLE));

        assertEquals(0, outcome.granted());
        assertFalse(outcome.anyDelivered());
        assertTrue(warnings.stream().anyMatch(w -> w.contains("Lootable")), "warnings: " + warnings);
    }

    @Test
    void aDroplistRewardThatNamesNoListIsNotCountedAsPaid() {
        RewardGrants.GrantOutcome outcome = grant(RewardSpec.of(DroplistRewardKind.KIND, "rolls", "2"));

        assertEquals(0, outcome.granted());
        assertFalse(outcome.anyDelivered());
        assertTrue(warnings.stream().anyMatch(w -> w.contains("Droplist")), "warnings: " + warnings);
    }

    @Test
    void aWellFormedItemRewardStillReachesItsGranter() {
        // With no live player the delivery itself cannot succeed here, but the parameter guard must
        // not be what stops it: the failure has to come from the grant, and it has to be replayable.
        RewardGrants.GrantOutcome outcome = grant(RewardSpec.of(LootRewardKinds.KIND_ITEM,
                Map.of("item", "Coin_Gold", "count", "3")));

        assertEquals(0, outcome.granted());
        assertEquals(1, outcome.queued(), "a named item is replayable, so a failed grant is queued");
        assertEquals(1, queued.size());
        assertNotNull(queued.get(0));
        assertTrue(queued.get(0).contains("Coin_Gold") && queued.get(0).contains("--quantity=3"),
                "the retry must hand over the same item and count, got: " + queued.get(0));
    }
}
