package com.ziggfreed.common.stats.gearset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.annotation.Nonnull;

import org.junit.jupiter.api.Test;

import com.ziggfreed.common.stats.EquippedSnapshot;
import com.ziggfreed.common.stats.gearset.GearSetDecision.SlotCounts;

/**
 * Pinned on plain ids: a tier's condition is a conjunction of independent minimums, never a bare
 * count. Three armor pieces plus the blade hold the two-piece tier and fail the weapon tier on
 * {@code Armor 3}; four armor pieces and no blade hold the armor tier and not the weapon tier; a
 * held copy of a worn piece counts once toward {@code Pieces} and never satisfies {@code Held} (a
 * spare in hand never stands in for the weapon), while a held member that is not worn does; the
 * utility slot counts for {@code Utility} and {@code Pieces} only.
 */
class GearSetDecisionTest {

    private static final int TWO_PIECES = 0;
    private static final int FULL_ARMOR = 1;
    private static final int ARMOR_AND_BLADE = 2;

    /** A five-member combat set: four armor pieces and a blade, the shipped ladder shape. */
    @Nonnull
    private static GearSetAsset nightSet() {
        return GearSetAsset.of("Night_Set", null, null,
                new String[] {"Night_Hood", "Night_Cuirass", "Night_Greaves", "Night_Gauntlets", "Night_Longsword"},
                GearSetAsset.Tier.of(2, null, null, null, null, null, null),
                GearSetAsset.Tier.of(null, 4, null, null, null, "Night_Set_Look", null),
                GearSetAsset.Tier.of(null, 4, 1, null, null, null, null));
    }

    @Nonnull
    private static EquippedSnapshot wearing(String held, String offhand, String... armor) {
        return EquippedSnapshot.of(held, offhand, new ArrayList<>(Arrays.asList(armor)));
    }

    @Test
    void threeArmorPiecesPlusTheBladeHoldTwoPiecesAndFailTheWeaponTierOnArmorThree() {
        GearSetAsset set = nightSet();
        SlotCounts counts = GearSetDecision.count(set,
                wearing("Night_Longsword", null, "Night_Hood", "Night_Cuirass", "Night_Greaves", null));

        assertEquals(new SlotCounts(4, 3, 1, 0), counts);
        assertEquals(List.of(TWO_PIECES), GearSetDecision.activeTiers(set, counts),
                "four members on holds Pieces 2; Armor 3 fails both Armor 4 tiers even with the blade in hand");
    }

    @Test
    void fourArmorPiecesAndNoBladeHoldTheArmorTierAndNotTheWeaponTier() {
        GearSetAsset set = nightSet();
        SlotCounts counts = GearSetDecision.count(set,
                wearing("Weapon_Sword_Iron", null, "Night_Hood", "Night_Cuirass", "Night_Greaves", "Night_Gauntlets"));

        assertEquals(new SlotCounts(4, 4, 0, 0), counts);
        assertEquals(List.of(TWO_PIECES, FULL_ARMOR), GearSetDecision.activeTiers(set, counts),
                "the look comes on with the armor; the blade tier waits for the blade");
    }

    @Test
    void theFullSetWithTheBladeHoldsEveryTier() {
        GearSetAsset set = nightSet();
        SlotCounts counts = GearSetDecision.count(set,
                wearing("night_longsword", null, "Night_Hood", "Night_Cuirass", "Night_Greaves", "Night_Gauntlets"));

        assertEquals(new SlotCounts(5, 4, 1, 0), counts);
        assertEquals(List.of(TWO_PIECES, FULL_ARMOR, ARMOR_AND_BLADE), GearSetDecision.activeTiers(set, counts),
                "tiers are cumulative by construction, and the held id matches without regard to case");
    }

    @Test
    void aHeldCopyOfAWornPieceCountsOnceAndIsNotHeld() {
        GearSetAsset set = nightSet();
        SlotCounts counts = GearSetDecision.count(set, wearing("Night_Hood", null, "Night_Hood", null, null, null));

        assertEquals(new SlotCounts(1, 1, 0, 0), counts,
                "Pieces counts DISTINCT members, so the hood in hand and the hood on the head are ONE piece; "
                        + "Held asks for a member in hand that is NOT also worn, and this hood is worn");
        assertTrue(GearSetDecision.activeTiers(set, counts).isEmpty(), "one piece holds nothing");
    }

    @Test
    void aSpareCopyOfAWornPieceInHandNeverStandsInForTheBlade() {
        GearSetAsset set = nightSet();
        SlotCounts counts = GearSetDecision.count(set,
                wearing("night_hood", null, "Night_Hood", "Night_Cuirass", "Night_Greaves", "Night_Gauntlets"));

        assertEquals(new SlotCounts(4, 4, 0, 0), counts,
                "the worn hood's spare copy in hand is compared by id without regard to case, and it is worn");
        assertEquals(List.of(TWO_PIECES, FULL_ARMOR), GearSetDecision.activeTiers(set, counts),
                "the full armor holds its own tiers; the blade tier still waits for the blade");
    }

    @Test
    void aHeldMemberThatIsNotWornIsHeld() {
        GearSetAsset set = nightSet();
        SlotCounts counts = GearSetDecision.count(set, wearing("Night_Hood", null, "Night_Cuirass", null, null, null));

        assertEquals(new SlotCounts(2, 1, 1, 0), counts,
                "a member in hand that no armor slot wears satisfies Held, and counts as a piece of its own");
        assertEquals(List.of(TWO_PIECES), GearSetDecision.activeTiers(set, counts));
    }

    @Test
    void theUtilitySlotCountsForUtilityAndPiecesOnly() {
        GearSetAsset set = GearSetAsset.of("Lantern_Set", null, null,
                new String[] {"Lantern", "Lantern_Hat"},
                GearSetAsset.Tier.of(2, null, null, null, null, null, null),
                GearSetAsset.Tier.of(null, null, null, 1, null, null, null),
                GearSetAsset.Tier.of(null, 2, null, null, null, null, null),
                GearSetAsset.Tier.of(null, null, 1, null, null, null, null));
        SlotCounts counts = GearSetDecision.count(set, wearing(null, "Lantern", "Lantern_Hat"));

        assertEquals(new SlotCounts(2, 1, 0, 1), counts);
        assertEquals(List.of(0, 1), GearSetDecision.activeTiers(set, counts),
                "the lantern in the utility slot is a piece and satisfies Utility 1; it is neither armor nor held");
    }

    @Test
    void aTierWithNoConditionNeverHoldsAndAMinimumOfZeroIsAlwaysMet() {
        GearSetAsset.Tier none = GearSetAsset.Tier.of(null, null, null, null, null, null, null);
        GearSetAsset.Tier zero = GearSetAsset.Tier.of(0, null, null, null, null, null, null);
        SlotCounts nothing = new SlotCounts(0, 0, 0, 0);

        assertFalse(GearSetDecision.holds(none, nothing), "no minimum at all is not 'always'; the validator names it");
        assertTrue(GearSetDecision.holds(zero, nothing), "the arithmetic holds; the validator refuses the file");
    }

    @Test
    void anItemNotInTheSetNeverCounts() {
        GearSetAsset set = nightSet();
        SlotCounts counts = GearSetDecision.count(set, wearing("Weapon_Sword_Iron", "Torch", "Armor_Iron_Head", null));

        assertEquals(new SlotCounts(0, 0, 0, 0), counts);
    }
}
