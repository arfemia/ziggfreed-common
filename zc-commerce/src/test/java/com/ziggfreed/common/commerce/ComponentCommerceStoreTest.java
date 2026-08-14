package com.ziggfreed.common.commerce;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ziggfreed.common.subject.Subject;

/**
 * What the component-backed store does with a subject it cannot resolve a component for.
 *
 * <p>That is the half worth pinning here, and it is not a corner case: an offline subject, a
 * maintenance pass with no world, and a unit test all look exactly like this. The happy path needs a
 * live entity store and belongs to in-game smoke; the STATE MACHINE behind it is pinned directly by
 * {@code CommerceComponentTest}, which is where the logic actually lives.
 *
 * <p>The rule being pinned: a read answers neutrally, a write is dropped rather than throwing, and a
 * MIGRATION claim refuses. The last one is the one that matters most - a claim that answered true
 * against state it cannot write would let a consumer's one-time migration run and vanish, and then
 * run again at the next login.
 */
class ComponentCommerceStoreTest {

    private static final Subject NOBODY = Subject.of(UUID.randomUUID(), "Nobody");

    private final ComponentCommerceStore store = ComponentCommerceStore.INSTANCE;

    @Test
    @DisplayName("every read answers neutrally for a subject with no component")
    void readsAreNeutral() {
        assertEquals(0L, store.balance(NOBODY, "Bounty_Token"));
        assertEquals(0L, store.lifetimeSpent(NOBODY, "Bounty_Token"));
        assertTrue(store.balances(NOBODY).isEmpty());
        assertEquals(0, store.purchasesToday(NOBODY, "offer", 100L));
        assertEquals(0, store.purchasesTotal(NOBODY, "offer"));
        assertTrue(store.rerollOverrides(NOBODY, "Daily", 5L).isEmpty());
        assertEquals(0, store.rerollsSpent(NOBODY, "Daily", 5L));
        assertEquals(1, store.rerollNextCount(NOBODY, "Daily", 5L, 0));
        assertTrue(store.rerollSeenAt(NOBODY, "Daily", 5L, 0).isEmpty());
        assertTrue(store.rerollState(NOBODY, "Daily", 5L).isEmpty());
        assertTrue(store.purchasedOfferIds(NOBODY).isEmpty());
        assertTrue(store.rerolledPoolIds(NOBODY).isEmpty());
        assertEquals(0L, store.rerollPeriod(NOBODY, "Daily"));
    }

    @Test
    @DisplayName("every write is dropped rather than thrown, and a reroll says it did not happen")
    void writesAreDropped() {
        store.setBalance(NOBODY, "Bounty_Token", 10L);
        store.recordSpend(NOBODY, "Bounty_Token", 10L);
        store.refundSpend(NOBODY, "Bounty_Token", 10L);
        store.setLifetimeSpent(NOBODY, "Bounty_Token", 10L);
        store.recordPurchase(NOBODY, "offer", 100L);
        store.setPurchases(NOBODY, "offer", 100L, 1, 1);
        store.clearPurchases(NOBODY);
        store.setRerolls(NOBODY, "Daily", RerollState.none(5L));
        store.clearRerolls(NOBODY);
        store.flush(NOBODY);

        assertFalse(store.commitReroll(NOBODY, "Daily", 5L, 3, 0, null, "whatever"),
                "a reroll that recorded nothing must not report success");
    }

    @Test
    @DisplayName("a migration cannot be claimed against state that cannot be written")
    void aMigrationRefusesWithoutAComponent() {
        assertFalse(store.claimMigration(NOBODY, "mymod:commerce-1"));
    }
}
