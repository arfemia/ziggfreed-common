package com.ziggfreed.common.stats.gearset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import javax.annotation.Nonnull;

import org.junit.jupiter.api.Test;

import com.ziggfreed.common.stats.EquippedSnapshot;

/**
 * The item-to-sets index: a recompute's candidates are the sets the player's items belong to, found
 * one lookup per item, in id order, a disabled set never among them; the looks a hydrate answers for
 * are every folded set's, a disabled set's included.
 */
class GearSetIndexTest {

    @Nonnull
    private static GearSetAsset set(@Nonnull String id, boolean enabled, @Nonnull String... members) {
        return GearSetAsset.of(id, null, enabled, members,
                GearSetAsset.Tier.of(2, null, null, null, null, null, null));
    }

    @Nonnull
    private static EquippedSnapshot wearing(String held, String offhand, String... armor) {
        return EquippedSnapshot.of(held, offhand, new ArrayList<>(Arrays.asList(armor)));
    }

    @Nonnull
    private static List<String> ids(@Nonnull List<GearSetAsset> sets) {
        List<String> out = new ArrayList<>();
        for (GearSetAsset set : sets) {
            out.add(set.getId());
        }
        return out;
    }

    private final GearSetIndex index = GearSetIndex.of(List.of(
            set("Zephyr_Set", true, "Zephyr_Hood", "Shared_Ring"),
            set("Amber_Set", true, "Amber_Hood", "Shared_Ring"),
            set("Night_Set", true, "Night_Hood", "Night_Blade"),
            set("Retired_Set", false, "Night_Hood", "Retired_Boots")));

    @Test
    void anItemFindsEverySetItBelongsToWithoutRegardToCase() {
        assertEquals(List.of("Amber_Set", "Zephyr_Set"), ids(index.setsFor("shared_RING")));
        assertTrue(index.setsFor("Loose_Item").isEmpty());
        assertTrue(index.setsFor(null).isEmpty());
    }

    @Test
    void theCandidatesAreTheSetsTheWornItemsBelongToInIdOrder() {
        List<GearSetAsset> candidates = index.candidates(wearing("Night_Blade", null, "Zephyr_Hood", "Shared_Ring"));

        assertEquals(List.of("Amber_Set", "Night_Set", "Zephyr_Set"), ids(candidates),
                "each set once, whichever of its members brought it in, sorted by id");
    }

    @Test
    void aDisabledSetIsNeverACandidate() {
        List<GearSetAsset> candidates = index.candidates(wearing(null, null, "Retired_Boots", "Night_Hood"));

        assertEquals(List.of("Night_Set"), ids(candidates));
    }

    @Test
    void nothingOnMeansNoCandidates() {
        assertTrue(index.candidates(EquippedSnapshot.EMPTY).isEmpty());
        assertTrue(index.candidates(wearing("Weapon_Sword_Iron", "Torch")).isEmpty());
    }

    @Test
    void everyEffectAnyFoldedSetNamesIsListedOnceADisabledSetsIncluded() {
        GearSetIndex withLooks = GearSetIndex.of(List.of(
                GearSetAsset.of("A_Set", null, null, new String[] {"A1", "A2"},
                        GearSetAsset.Tier.of(null, 2, null, null, null, "A_Look", null),
                        GearSetAsset.Tier.of(2, null, null, null, null, "A_Look", null)),
                GearSetAsset.of("B_Set", null, false, new String[] {"B1", "B2"},
                        GearSetAsset.Tier.of(2, null, null, null, null, "B_Look", null))));

        assertEquals(Set.of("A_Look", "B_Look"), withLooks.allEffectIds(),
                "a set switched off while its wearer was offline still has its saved look swept at login");
        assertTrue(withLooks.candidates(wearing(null, null, "B1", "B2")).isEmpty(),
                "while the disabled set itself stays out of every recompute's candidates");
    }
}
