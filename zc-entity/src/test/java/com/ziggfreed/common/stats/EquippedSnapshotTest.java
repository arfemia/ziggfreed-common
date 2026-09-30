package com.ziggfreed.common.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * The pure value the gear-set engine decides over: the distinct set is lower-cased and blank-free,
 * the armor list keeps its slot alignment (an empty slot stays a null entry at its index), and a
 * membership test ignores authored casing. Engine-free by construction.
 */
class EquippedSnapshotTest {

    @Test
    void theDistinctSetIsLowerCasedAndDropsBlanksAndDuplicates() {
        List<String> armor = new ArrayList<>(Arrays.asList("Ranger_Hood", null, "RANGER_COAT", " "));
        EquippedSnapshot snapshot = EquippedSnapshot.of("Ranger_Bow", "ranger_hood", armor);

        assertEquals(Set.of("ranger_bow", "ranger_hood", "ranger_coat"), snapshot.distinctItemIds(),
                "held, offhand and armor fold into one lower-cased set; a copy in two slots counts once");
        assertEquals("Ranger_Bow", snapshot.heldItemId(), "the held id keeps its authored spelling");
        assertEquals("ranger_hood", snapshot.offhandItemId());
    }

    @Test
    void theArmorListKeepsItsSlotAlignment() {
        List<String> armor = new ArrayList<>(Arrays.asList(null, "Ranger_Coat", null, "Ranger_Boots"));
        EquippedSnapshot snapshot = EquippedSnapshot.of(null, null, armor);

        assertEquals(4, snapshot.armorItemIds().size(), "one entry per armor slot, empty ones included");
        assertNull(snapshot.armorItemIds().get(0));
        assertEquals("Ranger_Coat", snapshot.armorItemIds().get(1));
        assertNull(snapshot.armorItemIds().get(2));
        assertEquals("Ranger_Boots", snapshot.armorItemIds().get(3));
    }

    @Test
    void membershipIgnoresCaseAndAnEmptySnapshotHasNothing() {
        EquippedSnapshot worn = EquippedSnapshot.of("Ranger_Bow", null, List.of());
        assertTrue(worn.has("RANGER_BOW"));
        assertFalse(worn.has("Ranger_Hood"));
        assertFalse(worn.has(null));
        assertFalse(worn.isEmpty());

        assertTrue(EquippedSnapshot.EMPTY.isEmpty());
        assertNull(EquippedSnapshot.EMPTY.heldItemId());
        assertTrue(EquippedSnapshot.EMPTY.armorItemIds().isEmpty());
    }

    @Test
    void aBlankHeldOrOffhandIdReadsAsNothingHeld() {
        EquippedSnapshot snapshot = EquippedSnapshot.of("  ", "", List.of());
        assertNull(snapshot.heldItemId());
        assertNull(snapshot.offhandItemId());
        assertTrue(snapshot.isEmpty());
    }
}
