package com.ziggfreed.common.stats.gearset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.junit.jupiter.api.Test;

import com.ziggfreed.common.stats.EquippedSnapshot;
import com.ziggfreed.common.stats.gearset.GearSetJoins.Dropped;
import com.ziggfreed.common.stats.gearset.GearSetJoins.Reason;
import com.ziggfreed.common.text.ContentTextAsset;

/**
 * The fold behind {@code Joins}, on the shape that bit: a mod's second set over the Hallowed pieces
 * gave a full set two sets, so two notices. Joined, the helm and the shield belong to ONE set whose
 * one tier carries both files' stats and lines and the set's own look; a layer's other tier comes
 * after the set's own; a layer with nothing to join, or joining a layer, is dropped and named; a set
 * no layer joins comes back untouched.
 */
class GearSetJoinsTest {

    private static final String[] HALLOWED = {"Hallows_Eve_Jack_Helm", "Hallows_Eve_Jack_Shield",
            "Hallows_Eve_Burning_Jack_Helm", "Hallows_Eve_Flaming_Jack_Shield"};

    @Nonnull
    private static GearSetAsset.Tier helmAndShield(@Nonnull String line, @Nullable String effect, @Nonnull String stat) {
        return GearSetAsset.Tier.of(null, 1, null, 1, ContentTextAsset.of(line, null, null), effect,
                Map.of(stat, new StatModifierSpec[] {StatModifierSpec.additive(1f)}));
    }

    @Nonnull
    private static GearSetAsset kit() {
        return GearSetAsset.of("Kit", null, null, new String[] {"Hood", "Coat"},
                GearSetAsset.Tier.of(2, null, null, null, null, null, null));
    }

    @Test
    void aFullHallowedSetIsOneSetWhoseOneTierCarriesBothFiles() {
        GearSetAsset pack = GearSetAsset.of("Hallows_Eve_Hallowed",
                ContentTextAsset.of("gearset.hallows_eve_hallowed.title", null, null), null, HALLOWED,
                helmAndShield("gearset.hallows_eve_hallowed.tier_full", "Hallows_Eve_Hallowed_Set", "Health"));
        GearSetAsset mmo = GearSetAsset.layer("Mmo_Hallows_Eve_Hallowed", "hallows_eve_HALLOWED", null,
                helmAndShield("gearset.mmo_hallows_eve_hallowed.tier_full", null, "MMO_Luck"));
        GearSetIndex index = GearSetIndex.of(List.of(mmo, pack));
        EquippedSnapshot worn = EquippedSnapshot.of(null, "Hallows_Eve_Jack_Shield",
                new ArrayList<>(List.of("Hallows_Eve_Jack_Helm")));

        List<GearSetAsset> candidates = index.candidates(worn);

        assertEquals(1, candidates.size(), "the helm and the shield belong to one set, so one notice goes out");
        GearSetAsset set = candidates.get(0);
        assertSame(set, index.set("HALLOWS_EVE_HALLOWED"), "set(id) answers the joined set");
        assertNull(index.set("Mmo_Hallows_Eve_Hallowed"), "a layer is no set of its own");
        assertEquals("gearset.hallows_eve_hallowed.title", set.titleKey(), "the set keeps its own name");
        assertEquals(2, GearSetDecision.fullCount(set), "and counts to its own full set");
        assertEquals(List.of(0), GearSetDecision.activeTiers(set, GearSetDecision.count(set, worn)),
                "the layer's tier asks what the set's tier asks, so it is that tier, not a second one");
        GearSetAsset.Tier tier = set.tiers().get(0);
        assertEquals(List.of("gearset.hallows_eve_hallowed.tier_full", "gearset.mmo_hallows_eve_hallowed.tier_full"),
                tier.titleKeys(), "the notice reads the set's line, then the layer's");
        assertEquals(Set.of("Health", "MMO_Luck"), tier.statModifiers().keySet(), "both files' stats apply");
        assertEquals("Hallows_Eve_Hallowed_Set", tier.effectId(), "the look stays the set's");
    }

    @Test
    void aLayersOtherTierComesAfterTheSetsOwnAndItsMembersJoin() {
        GearSetAsset kit = kit();
        GearSetAsset blade = GearSetAsset.layer("Kit_Blade", "Kit", new String[] {"Blade"},
                GearSetAsset.Tier.of(null, 2, 1, null, ContentTextAsset.of("kit.blade", null, null), null, null));

        GearSetAsset set = GearSetJoins.fold(List.of(kit, blade)).sets().get(0);

        assertEquals(Set.of("hood", "coat", "blade"), set.memberIds());
        assertEquals(2, set.tiers().size());
        assertSame(kit.tiers().get(0), set.tiers().get(0),
                "the set's own tier keeps its place, so the modifier keys written under it never move");
        assertEquals(List.of("kit.blade"), set.tiers().get(1).titleKeys());
    }

    @Test
    void aLayerWithNothingToJoinOrJoiningALayerIsDroppedAndASwitchedOffOneAddsNothing() throws IOException {
        GearSetAsset kit = kit();
        GearSetAsset off = GearSetAssetCodecTest.decode("Off",
                "{ \"Joins\": \"Kit\", \"Enabled\": false, \"Members\": [\"Extra\"] }", null);

        GearSetJoins.Folded folded = GearSetJoins.fold(List.of(kit, off,
                GearSetAsset.layer("Lost", "No_Such_Set", new String[] {"Extra"}),
                GearSetAsset.layer("Chained", "Lost", new String[] {"Extra"}),
                GearSetAsset.layer("Self", "self", new String[] {"Extra"})));

        assertEquals(List.of(new Dropped("Chained", "Lost", Reason.TARGET_IS_LAYER),
                new Dropped("Lost", "No_Such_Set", Reason.MISSING_TARGET),
                new Dropped("Self", "self", Reason.TARGET_IS_LAYER)), folded.dropped(), "in layer id order");
        assertEquals(List.of(kit), folded.sets());
        assertSame(kit, folded.sets().get(0), "a set no layer joins comes back as it was");
    }
}
