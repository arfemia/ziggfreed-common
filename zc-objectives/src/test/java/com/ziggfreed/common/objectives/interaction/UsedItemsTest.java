package com.ziggfreed.common.objectives.interaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/** Which item a chain is about, when the hand may no longer hold it. */
class UsedItemsTest {

    @Test
    void theItemTheChainStartedWithWinsOverTheHand() {
        assertEquals("Weapon_Bomb", UsedItems.pick("Weapon_Bomb", "Weapon_Bomb_Broken"),
                "a step that swapped what is in hand did not change what was used");
    }

    @Test
    void theLastOfAStackSpentEarlierInTheChainIsStillTheItemUsed() {
        assertEquals("Weapon_Bomb", UsedItems.pick("Weapon_Bomb", null),
                "a ModifyInventory emptied the hand, but the chain started with the bomb");
    }

    @Test
    void aChainThatStartedEmptyHandedReadsTheHand() {
        assertEquals("Rock_Geode", UsedItems.pick(null, "Rock_Geode"));
        assertEquals("Rock_Geode", UsedItems.pick("  ", " Rock_Geode "));
    }

    @Test
    void nothingAnywhereNamesNothing() {
        assertNull(UsedItems.pick(null, null));
        assertNull(UsedItems.pick(" ", ""));
        assertNull(UsedItems.id(null));
    }
}
