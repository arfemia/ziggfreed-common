package com.ziggfreed.common.loot.trigger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Set;

import org.junit.jupiter.api.Test;

import com.ziggfreed.common.loot.reward.MomentItems;

/** How a bonus moment is spelled in a row, and which collectors its pass carries. */
class BonusMomentTest {

    @Test
    void eachMomentIsSpelledTheWayARowWritesIt() {
        assertEquals("BreakBlock", BonusMoment.BREAK_BLOCK.token());
        assertEquals("KillMob", BonusMoment.KILL_MOB.token());
        assertEquals("PickupItem", BonusMoment.PICKUP_ITEM.token());
        assertEquals("BreakBlock, KillMob, PickupItem", BonusMoment.tokens());
    }

    @Test
    void caseAndUnderscoresAreForgiven() {
        assertEquals(BonusMoment.PICKUP_ITEM, BonusMoment.parse("PickupItem"));
        assertEquals(BonusMoment.PICKUP_ITEM, BonusMoment.parse("Pickup_Item"));
        assertEquals(BonusMoment.KILL_MOB, BonusMoment.parse(" killmob "));
        assertEquals(BonusMoment.BREAK_BLOCK, BonusMoment.parse("BREAK_BLOCK"));
    }

    @Test
    void anythingElseNamesNoMoment() {
        assertNull(BonusMoment.parse(null));
        assertNull(BonusMoment.parse(""));
        assertNull(BonusMoment.parse("  _ "));
        assertNull(BonusMoment.parse("Sneeze"));
        assertNull(BonusMoment.parse("KILL_ENTITY"), "the objective kind id is not a bonus moment's spelling");
    }

    @Test
    void onlyAHarvestCarriesTheCopyCollector() {
        assertEquals(Set.of(MomentItems.class), BonusMoment.PICKUP_ITEM.carries());
        assertEquals(Set.of(), BonusMoment.BREAK_BLOCK.carries());
        assertEquals(Set.of(), BonusMoment.KILL_MOB.carries());
    }
}
