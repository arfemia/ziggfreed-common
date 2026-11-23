package com.ziggfreed.common.track.source;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

/**
 * Each diminishing key reads the part of a moment it switches on, and only that: two moments that differ only in a
 * part the key leaves out count together, two that differ in a part it reads count apart. The shapes of the five
 * keys the library ships, built here, not read from their files.
 */
class DiminishKeyTest {

    private static DiminishKeyAsset.Facts at(String target, String victim, String station, int x, int y, int z) {
        return new DiminishKeyAsset.Facts(target, "Weapon_Sword_Iron", victim, station, "default", true, x, y, z);
    }

    private static final DiminishKeyAsset CELL16 = DiminishKeyAsset.of("Cell16", false, false, false, false, 16);
    private static final DiminishKeyAsset POSITION = DiminishKeyAsset.of("Position", false, false, false, false, 1);
    private static final DiminishKeyAsset TARGET = DiminishKeyAsset.of("Target", true, false, false, false, 0);
    private static final DiminishKeyAsset STATION = DiminishKeyAsset.of("Station", false, false, false, true, 0);
    private static final DiminishKeyAsset VICTIM = DiminishKeyAsset.of("Victim", false, false, true, false, 0);

    @Test
    void aCellKeyCountsEachCubeOfTheWorldApartNegativeCoordinatesIncluded() {
        assertEquals(CELL16.valueOf(at("Trork", "a", null, 1, 64, 1)), CELL16.valueOf(at("Wolf", "b", null, 15, 70, 15)),
                "one cube whatever was killed there");
        assertNotEquals(CELL16.valueOf(at("Trork", "a", null, 15, 64, 0)),
                CELL16.valueOf(at("Trork", "a", null, 16, 64, 0)), "16 starts the next cube");
        assertNotEquals(CELL16.valueOf(at("Trork", "a", null, 0, 64, 0)),
                CELL16.valueOf(at("Trork", "a", null, -1, 64, 0)), "-1 sits in the cube below 0, not beside it");
        assertEquals(CELL16.valueOf(at("Trork", "a", null, -1, 64, 0)),
                CELL16.valueOf(at("Trork", "a", null, -16, 64, 0)));
        DiminishKeyAsset.Facts elsewhere = new DiminishKeyAsset.Facts("Trork", null, null, null, "other", true,
                1, 64, 1);
        assertNotEquals(CELL16.valueOf(at("Trork", "a", null, 1, 64, 1)), CELL16.valueOf(elsewhere),
                "each world apart");
    }

    @Test
    void aPositionKeyCountsEachBlockApart() {
        assertEquals(POSITION.valueOf(at("Plank", null, null, 3, 64, 3)),
                POSITION.valueOf(at("Stone", null, null, 3, 64, 3)), "one spot whatever is placed there");
        assertNotEquals(POSITION.valueOf(at("Plank", null, null, 3, 64, 3)),
                POSITION.valueOf(at("Plank", null, null, 3, 65, 3)));
    }

    @Test
    void aTargetKeyCountsEachKindApartWhereverItIs() {
        assertEquals(TARGET.valueOf(at("Ore_Iron", null, null, 0, 0, 0)),
                TARGET.valueOf(at("ore_iron", null, null, 500, 10, -500)), "case and place do not matter");
        assertNotEquals(TARGET.valueOf(at("Ore_Iron", null, null, 0, 0, 0)),
                TARGET.valueOf(at("Ore_Copper", null, null, 0, 0, 0)));
    }

    @Test
    void aStationKeyCountsEachStationAndMomentsWithoutOneTogether() {
        assertNotEquals(STATION.valueOf(at("Plank", null, "sawmill@1,2,3", 0, 0, 0)),
                STATION.valueOf(at("Plank", null, "sawmill@4,5,6", 0, 0, 0)));
        assertEquals(STATION.valueOf(at("Plank", null, null, 0, 0, 0)),
                STATION.valueOf(at("Log", null, " ", 9, 9, 9)), "no station reads as one shared value");
    }

    @Test
    void aVictimKeyCountsEachEntityApart() {
        assertEquals(VICTIM.valueOf(at("Player", "11", null, 0, 0, 0)),
                VICTIM.valueOf(at("Player", "11", null, 90, 0, 90)));
        assertNotEquals(VICTIM.valueOf(at("Player", "11", null, 0, 0, 0)),
                VICTIM.valueOf(at("Player", "22", null, 0, 0, 0)));
    }

    @Test
    void aKeyMixesEveryPartItSwitchesOnAndOneWithNoneCountsEverythingTogether() {
        DiminishKeyAsset targetInCell = DiminishKeyAsset.of("Mix", true, false, false, false, 8);
        assertNotEquals(targetInCell.valueOf(at("A", null, null, 0, 0, 0)),
                targetInCell.valueOf(at("B", null, null, 0, 0, 0)));
        assertNotEquals(targetInCell.valueOf(at("A", null, null, 0, 0, 0)),
                targetInCell.valueOf(at("A", null, null, 8, 0, 0)));
        DiminishKeyAsset nothing = DiminishKeyAsset.of("None", false, false, false, false, 0);
        assertEquals("", nothing.valueOf(at("A", "v", "s", 1, 2, 3)));
        DiminishKeyAsset.Facts unplaced = new DiminishKeyAsset.Facts("A", null, null, null, "default", false,
                0, 0, 0);
        assertEquals(CELL16.valueOf(unplaced), CELL16.valueOf(new DiminishKeyAsset.Facts("B", null, null, null,
                "other", false, 5, 5, 5)), "a moment with no place reads as one shared value");
    }
}
