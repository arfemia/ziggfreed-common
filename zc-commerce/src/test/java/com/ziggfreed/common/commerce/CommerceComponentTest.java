package com.ziggfreed.common.commerce;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The persisted state machine the component-backed store delegates to. The store itself is a
 * component lookup and a call, so this is where the behaviour actually lives.
 *
 * <p>The codec is asserted for static initialization only - that is what catches a lower-case
 * {@code KeyedCodec} key at build time rather than at server start. An encode / decode pass needs a
 * running asset registry, so it belongs to in-game smoke; what a saved world would HOLD is pinned by
 * {@code CommerceBlobTest}.
 */
class CommerceComponentTest {

    @Test
    @DisplayName("the codec static initializes")
    void theCodecStaticInitializes() {
        assertNotNull(CommerceComponent.CODEC,
                "a lower-case KeyedCodec key would throw here rather than at server start");
    }

    @Test
    @DisplayName("an empty component reads neutral everywhere")
    void anEmptyComponentReadsNeutral() {
        CommerceComponent component = new CommerceComponent();

        assertEquals(0L, component.balance("Bounty_Token"));
        assertEquals(0L, component.lifetimeSpent("Bounty_Token"));
        assertTrue(component.balances().isEmpty());
        assertEquals(0, component.purchasesToday("offer", 100L));
        assertEquals(0, component.purchasesTotal("offer"));
        assertTrue(component.purchasedOfferIds().isEmpty());
        assertTrue(component.rerollOverrides("Daily", 5L).isEmpty());
        assertEquals(0, component.rerollsSpent("Daily", 5L));
        assertEquals(1, component.rerollNextCount("Daily", 5L, 0));
        assertTrue(component.rerollSeenAt("Daily", 5L, 0).isEmpty());
        assertTrue(component.rerollState("Daily", 5L).isEmpty());
        assertTrue(component.rerolledPoolIds().isEmpty());
        assertFalse(component.hasMigrated("mymod:whatever"));
    }

    // ==================== the wallet ====================

    @Test
    @DisplayName("a balance of zero is stored as absence, so an emptied wallet leaves nothing behind")
    void anEmptiedWalletLeavesNothing() {
        CommerceComponent component = new CommerceComponent();
        component.setBalance("Bounty_Token", 40L);
        assertEquals(Map.of("Bounty_Token", Long.valueOf(40L)), component.balances());

        component.setBalance("Bounty_Token", 0L);
        assertTrue(component.balances().isEmpty());
    }

    @Test
    @DisplayName("a lifetime tally adds, refunds down to zero, and can be written outright")
    void theLifetimeTallyIsAdditiveAndAbsolute() {
        CommerceComponent component = new CommerceComponent();
        component.addLifetimeSpent("Bounty_Token", 30L);
        component.addLifetimeSpent("Bounty_Token", 12L);
        assertEquals(42L, component.lifetimeSpent("Bounty_Token"));

        component.refundLifetimeSpent("Bounty_Token", 100L);
        assertEquals(0L, component.lifetimeSpent("Bounty_Token"), "a refund never goes below zero");

        component.setLifetimeSpent("Bounty_Token", 7L);
        assertEquals(7L, component.lifetimeSpent("Bounty_Token"));
    }

    // ==================== purchase limits ====================

    @Test
    @DisplayName("the daily count resets when the day number moves and the total never does")
    void theDayRollsOverWithNoSweep() {
        CommerceComponent component = new CommerceComponent();
        component.recordPurchase("boost", 100L);
        component.recordPurchase("boost", 100L);

        assertEquals(2, component.purchasesToday("boost", 100L));
        assertEquals(0, component.purchasesToday("boost", 101L));
        assertEquals(2, component.purchasesTotal("boost"));

        component.recordPurchase("boost", 101L);
        assertEquals(1, component.purchasesToday("boost", 101L));
        assertEquals(3, component.purchasesTotal("boost"));
    }

    @Test
    @DisplayName("counts can be written outright, which one-at-a-time recording could never reproduce")
    void countsCanBeWrittenOutright() {
        CommerceComponent component = new CommerceComponent();
        component.setPurchases("boost", 100L, 2, 17);

        assertEquals(2, component.purchasesToday("boost", 100L));
        assertEquals(17, component.purchasesTotal("boost"));
        assertEquals(Set.of("boost"), component.purchasedOfferIds());
    }

    @Test
    @DisplayName("writing all-zero counts, and clearing, both leave nothing behind")
    void clearingLeavesNothing() {
        CommerceComponent component = new CommerceComponent();
        component.recordPurchase("boost", 100L);
        component.recordPurchase("packet", 100L);

        component.setPurchases("boost", 100L, 0, 0);
        assertEquals(Set.of("packet"), component.purchasedOfferIds());

        component.clearPurchases();
        assertTrue(component.purchasedOfferIds().isEmpty());
    }

    // ==================== rerolls ====================

    @Test
    @DisplayName("a committed reroll sets the override, counts the position and remembers both ids")
    void aCommittedRerollRecordsEverything() {
        CommerceComponent component = new CommerceComponent();

        assertTrue(component.commitReroll("Daily", 5L, 3, 0, "old_one", "new_one"));

        assertEquals(Map.of(Integer.valueOf(0), "new_one"), component.rerollOverrides("Daily", 5L));
        assertEquals(1, component.rerollsSpent("Daily", 5L));
        assertEquals(2, component.rerollNextCount("Daily", 5L, 0), "the NEXT draw is seeded by 2");
        assertEquals(Set.of("old_one", "new_one"), component.rerollSeenAt("Daily", 5L, 0));
    }

    @Test
    @DisplayName("the cap refuses without mutating anything, so a caller can charge around it")
    void theCapIsCheckedAtomically() {
        CommerceComponent component = new CommerceComponent();
        assertTrue(component.commitReroll("Daily", 5L, 1, 0, null, "first"));

        assertFalse(component.commitReroll("Daily", 5L, 1, 1, null, "second"));
        assertEquals(1, component.rerollsSpent("Daily", 5L));
        assertEquals(Map.of(Integer.valueOf(0), "first"), component.rerollOverrides("Daily", 5L),
                "a refused reroll must leave the state exactly as it was");
    }

    @Test
    @DisplayName("a new period reads as empty and replaces the old record on the first write")
    void aNewPeriodStartsClean() {
        CommerceComponent component = new CommerceComponent();
        component.commitReroll("Daily", 5L, 3, 0, null, "first");

        assertTrue(component.rerollOverrides("Daily", 6L).isEmpty());
        assertEquals(0, component.rerollsSpent("Daily", 6L));

        component.commitReroll("Daily", 6L, 3, 1, null, "second");
        assertEquals(1, component.rerollsSpent("Daily", 6L));
        assertEquals(Map.of(Integer.valueOf(1), "second"), component.rerollOverrides("Daily", 6L));
        assertTrue(component.rerollOverrides("Daily", 5L).isEmpty(), "the old period is gone, not kept");
    }

    @Test
    @DisplayName("two pools keep their own state, even where one id is a prefix of the other")
    void poolsAreKeptApart() {
        CommerceComponent component = new CommerceComponent();
        component.commitReroll("Daily", 5L, 3, 0, null, "daily_pick");
        component.commitReroll("Daily_Extra", 5L, 3, 0, null, "extra_pick");

        assertEquals(Map.of(Integer.valueOf(0), "daily_pick"), component.rerollOverrides("Daily", 5L));
        assertEquals(Map.of(Integer.valueOf(0), "extra_pick"),
                component.rerollOverrides("Daily_Extra", 5L));
    }

    @Test
    @DisplayName("a whole reroll state can be written back and cleared")
    void rerollStateIsAbsolute() {
        CommerceComponent component = new CommerceComponent();
        component.setRerolls("Daily", new RerollState(9L, 2,
                Map.of(Integer.valueOf(0), "shown"), Map.of(Integer.valueOf(0), Integer.valueOf(2)),
                Map.of(Integer.valueOf(0), Set.of("gone", "shown"))));

        RerollState read = component.rerollState("Daily", 9L);
        assertEquals(2, read.spent());
        assertEquals("shown", read.overrideAt(0));
        assertEquals(2, read.countAt(0));
        assertEquals(Set.of("gone", "shown"), read.seenAt(0));
        assertEquals(3, component.rerollNextCount("Daily", 9L, 0));

        component.clearRerolls();
        assertTrue(component.rerollState("Daily", 9L).isEmpty());
    }

    // ==================== migrations ====================

    @Test
    @DisplayName("a migration is claimable exactly once, and each id is its own claim")
    void aMigrationIsClaimedOnce() {
        CommerceComponent component = new CommerceComponent();

        assertTrue(component.claimMigration("mymod:commerce-1"));
        assertFalse(component.claimMigration("mymod:commerce-1"));
        assertTrue(component.hasMigrated("mymod:commerce-1"));
        assertTrue(component.claimMigration("othermod:commerce-1"),
                "two consumers must not share one claim");
    }

    // ==================== copying ====================

    @Test
    @DisplayName("a clone carries every leaf and shares no map with the original")
    void cloneIsDeep() {
        CommerceComponent original = new CommerceComponent();
        original.setBalance("Bounty_Token", 40L);
        original.addLifetimeSpent("Bounty_Token", 10L);
        original.recordPurchase("boost", 100L);
        original.commitReroll("Daily", 5L, 3, 0, "gone", "shown");
        original.claimMigration("mymod:commerce-1");

        CommerceComponent copy = original.clone();
        assertEquals(40L, copy.balance("Bounty_Token"));
        assertEquals(10L, copy.lifetimeSpent("Bounty_Token"));
        assertEquals(1, copy.purchasesTotal("boost"));
        assertEquals(Map.of(Integer.valueOf(0), "shown"), copy.rerollOverrides("Daily", 5L));
        assertTrue(copy.hasMigrated("mymod:commerce-1"));

        copy.setBalance("Bounty_Token", 1L);
        copy.recordPurchase("boost", 100L);
        assertEquals(40L, original.balance("Bounty_Token"));
        assertEquals(1, original.purchasesTotal("boost"));
    }
}
