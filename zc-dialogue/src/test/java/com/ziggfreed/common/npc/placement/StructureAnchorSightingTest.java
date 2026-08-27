package com.ziggfreed.common.npc.placement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The structure-anchor sighting contract: a sighting is identified by the marker's floored world
 * position (the {@code String} instance id), never by anything else on the marker entity - a
 * marker's uuid is fresh on every synthesis and an instance-loaded marker carries no other stable
 * component - so two sightings of one spot must collapse to one anchor while two spots sharing a
 * marker asset id must stay two.
 */
class StructureAnchorSightingTest {

    private static StructureAnchorIndex.Marker marker(String markerId, String instanceId, String... roles) {
        return new StructureAnchorIndex.Marker(markerId, List.of(roles), instanceId, 0, 0, 0);
    }

    @AfterEach
    void tearDown() {
        StructureMarkerSightings.getInstance().clearForTests();
    }

    // ==================== matching (the pure selection) ====================

    @Test
    void anchorWithNoMatcherAuthoredMatchesNothing() {
        NpcPlacementAsset.Anchor.Structure anchor =
                NpcPlacementAsset.Anchor.Structure.of(null, null, null, null, null);
        List<StructureAnchorIndex.Marker> markers =
                List.of(marker("Temple_Kweebec_Merchant_Static", "5095_169_4984"));
        assertTrue(StructureAnchorIndex.matching(markers, anchor).isEmpty());
    }

    @Test
    void matchesByMarkerIdCaseInsensitively() {
        NpcPlacementAsset.Anchor.Structure anchor = NpcPlacementAsset.Anchor.Structure.of(
                new String[] {"temple_kweebec_merchant_static"}, null, null, null, null);
        List<StructureAnchorIndex.Marker> markers = List.of(
                marker("Temple_Kweebec_Merchant_Static", "5095_169_4984"),
                marker("Temple_Guard_Static", "5100_169_4990"));
        List<StructureAnchorIndex.Marker> matched = StructureAnchorIndex.matching(markers, anchor);
        assertEquals(1, matched.size());
        assertEquals("5095_169_4984", matched.get(0).instanceId());
    }

    @Test
    void matchesByRoleTheMarkerCanSpawn() {
        NpcPlacementAsset.Anchor.Structure anchor = NpcPlacementAsset.Anchor.Structure.of(
                null, new String[] {"Temple_Kweebec_Merchant"}, null, null, null);
        List<StructureAnchorIndex.Marker> markers = List.of(
                marker("Temple_Kweebec_Merchant_Static", "5095_169_4984", "Temple_Kweebec_Merchant"),
                marker("Temple_Guard_Static", "5100_169_4990", "Temple_Guard"));
        List<StructureAnchorIndex.Marker> matched = StructureAnchorIndex.matching(markers, anchor);
        assertEquals(1, matched.size());
        assertEquals("Temple_Kweebec_Merchant_Static", matched.get(0).markerId());
    }

    @Test
    void twoBlocksSharingOneMarkerAssetAreTwoAnchorInstances() {
        // An ambient marker asset placed twice in one structure: same marker id, two block
        // positions. Both must survive matching as their own instance, or one NPC would stand
        // where two were authored.
        NpcPlacementAsset.Anchor.Structure anchor = NpcPlacementAsset.Anchor.Structure.of(
                new String[] {"Temple_Sporefly"}, null, null, null, null);
        List<StructureAnchorIndex.Marker> markers = List.of(
                marker("Temple_Sporefly", "5080_170_4970"),
                marker("Temple_Sporefly", "5120_168_5010"));
        assertEquals(2, StructureAnchorIndex.matching(markers, anchor).size());
    }

    // ==================== the sightings ring buffer ====================

    @Test
    void resightingOneBlockBumpsTheRowInsteadOfAddingASecond() {
        StructureMarkerSightings log = StructureMarkerSightings.getInstance();
        log.record("temple", "Temple_Kweebec_Merchant_Static", 5095.5, 169.0, 4984.5,
                "5095_169_4984", List.of("Temple_Kweebec_Merchant"));
        log.record("temple", "Temple_Kweebec_Merchant_Static", 5095.5, 169.0, 4984.5,
                "5095_169_4984", List.of("Temple_Kweebec_Merchant"));
        List<StructureMarkerSightings.Sighting> rows = log.listForWorld("temple");
        assertEquals(1, rows.size());
        assertEquals(2, rows.get(0).count());
    }

    @Test
    void aSecondBlockOfTheSameMarkerAssetIsItsOwnRow() {
        StructureMarkerSightings log = StructureMarkerSightings.getInstance();
        log.record("temple", "Temple_Sporefly", 5080, 170, 4970, "5080_170_4970", List.of());
        log.record("temple", "Temple_Sporefly", 5120, 168, 5010, "5120_168_5010", List.of());
        assertEquals(2, log.listForWorld("temple").size());
        assertFalse(log.listForWorld("other").iterator().hasNext());
    }
}
