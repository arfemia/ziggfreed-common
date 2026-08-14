package com.ziggfreed.common.commerce;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ziggfreed.common.subject.Subject;

/** The bookkeeping every purchase limit and every reroll cap is decided on. */
class CommerceStoreTest {

    private static final Subject ANNE = Subject.of(UUID.randomUUID(), "Anne");
    private static final Subject BOB = Subject.of(UUID.randomUUID(), "Bob");

    private InMemoryCommerceStore store;

    @BeforeEach
    void setUp() {
        store = new InMemoryCommerceStore();
    }

    @Test
    @DisplayName("a subject nobody has recorded anything about answers neutrally")
    void anUnknownSubjectAnswersNeutrally() {
        assertEquals(0L, store.balance(ANNE, "Bounty_Token"));
        assertEquals(0, store.purchasesToday(ANNE, "offer", 1L));
        assertEquals(0, store.purchasesTotal(ANNE, "offer"));
        assertTrue(store.rerollOverrides(ANNE, "Daily", 5L).isEmpty());
        assertEquals(0, store.rerollsSpent(ANNE, "Daily", 5L));
        assertEquals(1, store.rerollNextCount(ANNE, "Daily", 5L, 0));
    }

    @Test
    @DisplayName("purchase counts are per subject and per offer")
    void purchasesAreKeptApart() {
        store.recordPurchase(ANNE, "boost_mining", 100L);
        store.recordPurchase(ANNE, "boost_mining", 100L);
        store.recordPurchase(ANNE, "xp_packet", 100L);
        store.recordPurchase(BOB, "boost_mining", 100L);

        assertEquals(2, store.purchasesTotal(ANNE, "boost_mining"));
        assertEquals(1, store.purchasesTotal(ANNE, "xp_packet"));
        assertEquals(1, store.purchasesTotal(BOB, "boost_mining"));
    }

    @Test
    @DisplayName("the daily count resets when the day number moves; the total never does")
    void theDailyCountResetsAndTheTotalDoesNot() {
        store.recordPurchase(ANNE, "boost_mining", 100L);
        store.recordPurchase(ANNE, "boost_mining", 100L);
        assertEquals(2, store.purchasesToday(ANNE, "boost_mining", 100L));

        assertEquals(0, store.purchasesToday(ANNE, "boost_mining", 101L), "a new day, a clean count");
        store.recordPurchase(ANNE, "boost_mining", 101L);
        assertEquals(1, store.purchasesToday(ANNE, "boost_mining", 101L));
        assertEquals(3, store.purchasesTotal(ANNE, "boost_mining"));
    }

    @Test
    @DisplayName("a lifetime spend accumulates and a refund takes back only its own share")
    void spendAccumulatesAndRefundsUndoTheirShare() {
        store.recordSpend(ANNE, "Bounty_Token", 30);
        store.recordSpend(ANNE, "Bounty_Token", 20);
        assertEquals(50L, store.lifetimeSpent(ANNE, "Bounty_Token"));

        store.refundSpend(ANNE, "Bounty_Token", 20);
        assertEquals(30L, store.lifetimeSpent(ANNE, "Bounty_Token"));

        store.refundSpend(ANNE, "Bounty_Token", 999);
        assertEquals(0L, store.lifetimeSpent(ANNE, "Bounty_Token"), "never below zero");
    }

    @Test
    @DisplayName("a committed reroll records the override, the count, and both ids at that position")
    void aCommittedRerollRecordsEverything() {
        assertTrue(store.commitReroll(ANNE, "Daily", 5L, 3, 1, "bounty_a", "bounty_b"));

        assertEquals("bounty_b", store.rerollOverrides(ANNE, "Daily", 5L).get(1));
        assertEquals(1, store.rerollsSpent(ANNE, "Daily", 5L));
        assertEquals(2, store.rerollNextCount(ANNE, "Daily", 5L, 1));
        assertTrue(store.rerollSeenAt(ANNE, "Daily", 5L, 1).containsAll(Set.of("bounty_a", "bounty_b")));
    }

    @Test
    @DisplayName("the cap is checked atomically, so a refused commit changes nothing")
    void theCapRefusesWithoutMutating() {
        assertTrue(store.commitReroll(ANNE, "Daily", 5L, 2, 0, null, "b1"));
        assertTrue(store.commitReroll(ANNE, "Daily", 5L, 2, 1, null, "b2"));

        assertFalse(store.commitReroll(ANNE, "Daily", 5L, 2, 2, null, "b3"));
        assertEquals(2, store.rerollsSpent(ANNE, "Daily", 5L));
        assertFalse(store.rerollOverrides(ANNE, "Daily", 5L).containsKey(2));
    }

    @Test
    @DisplayName("a cap of zero is uncapped rather than a ban")
    void aZeroCapIsUncapped() {
        for (int i = 0; i < 20; i++) {
            assertTrue(store.commitReroll(ANNE, "Daily", 5L, 0, i, null, "b" + i));
        }
        assertEquals(20, store.rerollsSpent(ANNE, "Daily", 5L));
    }

    @Test
    @DisplayName("a rotation rollover wipes reroll state with no sweep anywhere")
    void rolloverWipesRerollState() {
        store.commitReroll(ANNE, "Daily", 5L, 3, 0, null, "b1");
        assertEquals(1, store.rerollsSpent(ANNE, "Daily", 5L));

        assertEquals(0, store.rerollsSpent(ANNE, "Daily", 6L));
        assertTrue(store.rerollOverrides(ANNE, "Daily", 6L).isEmpty());
        assertTrue(store.rerollSeenAt(ANNE, "Daily", 6L, 0).isEmpty());
    }

    @Test
    @DisplayName("two pools keep their own reroll state in one period")
    void poolsAreKeptApart() {
        store.commitReroll(ANNE, "Daily", 5L, 3, 0, null, "b1");
        assertEquals(0, store.rerollsSpent(ANNE, "Weekly", 5L));
        assertTrue(store.rerollOverrides(ANNE, "Weekly", 5L).isEmpty());
    }

    @Test
    @DisplayName("a counter balance never goes below zero")
    void balancesNeverGoNegative() {
        store.setBalance(ANNE, "Bounty_Token", -50);
        assertEquals(0L, store.balance(ANNE, "Bounty_Token"));
    }

    @Test
    @DisplayName("the in-memory store says it genuinely keeps both kinds of state")
    void capabilityProbesAreHonest() {
        assertTrue(store.recordsPurchases());
        assertTrue(store.recordsRerolls());
    }

    // ==================== writing state in, and putting it back ====================

    @Test
    @DisplayName("counts and tallies can be written outright, so an import that runs twice is the same")
    void theWriteItInSurfaceIsAbsolute() {
        store.setPurchases(ANNE, "boost_mining", 100L, 2, 17);
        store.setLifetimeSpent(ANNE, "Bounty_Token", 900L);

        store.setPurchases(ANNE, "boost_mining", 100L, 2, 17);
        store.setLifetimeSpent(ANNE, "Bounty_Token", 900L);

        assertEquals(2, store.purchasesToday(ANNE, "boost_mining", 100L));
        assertEquals(17, store.purchasesTotal(ANNE, "boost_mining"));
        assertEquals(900L, store.lifetimeSpent(ANNE, "Bounty_Token"));
    }

    @Test
    @DisplayName("a whole reroll state goes in and comes back, and clearing takes every pool")
    void rerollStateIsWrittenAndCleared() {
        store.setRerolls(ANNE, "Daily", new RerollState(9L, 2,
                Map.of(Integer.valueOf(0), "shown"), Map.of(Integer.valueOf(0), Integer.valueOf(2)),
                Map.of(Integer.valueOf(0), Set.of("gone", "shown"))));

        RerollState read = store.rerollState(ANNE, "Daily", 9L);
        assertEquals(2, read.spent());
        assertEquals("shown", read.overrideAt(0));
        assertEquals(Set.of("gone", "shown"), read.seenAt(0));
        assertEquals(3, store.rerollNextCount(ANNE, "Daily", 9L, 0));

        store.clearRerolls(ANNE);
        assertTrue(store.rerollState(ANNE, "Daily", 9L).isEmpty());
    }

    @Test
    @DisplayName("clearing purchases takes every offer, and only for that subject")
    void clearingPurchasesIsPerSubject() {
        store.recordPurchase(ANNE, "boost_mining", 100L);
        store.recordPurchase(BOB, "boost_mining", 100L);

        store.clearPurchases(ANNE);

        assertEquals(0, store.purchasesTotal(ANNE, "boost_mining"));
        assertEquals(1, store.purchasesTotal(BOB, "boost_mining"));
    }

    @Test
    @DisplayName("a migration is claimable once per subject, per id")
    void aMigrationIsClaimedOnce() {
        assertTrue(store.claimMigration(ANNE, "mymod:commerce-1"));
        assertFalse(store.claimMigration(ANNE, "mymod:commerce-1"));
        assertTrue(store.claimMigration(BOB, "mymod:commerce-1"),
                "one player's migration says nothing about another's");
    }
}
