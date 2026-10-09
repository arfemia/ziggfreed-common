package com.ziggfreed.common.npc.placement.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ziggfreed.common.npc.NpcIdentityConfig;
import com.ziggfreed.common.npc.placement.asset.NpcPlacementAsset;
import com.ziggfreed.common.npc.placement.asset.NpcPlacementConfig;
import com.ziggfreed.common.world.WorldSelector;

/** Where a character stands: under which worlds' Where, and at which cached positions in one world. */
class PlacementWorldsTest {

    private static final WorldSelector TEMPLE = WorldSelector.of(null, new String[]{"ForgottenTemple"}, null);
    private static final String TEMPLE_WORLD = "instance-forgotten-temple-goblins";

    @BeforeEach
    @AfterEach
    void clear() {
        NpcPlacementConfig.getInstance().mergePackLayer(Map.of());
        NpcIdentityConfig.getInstance().mergePackLayer(Map.of());
        NpcPlacementPositionCache.invalidateAll();
    }

    private static NpcPlacementAsset standing(String id, String role, Boolean enabled, WorldSelector where) {
        return NpcPlacementAsset.of(id, enabled, NpcPlacementAsset.Identity.of(role), where,
                null, null, null, null, null);
    }

    private static void load(NpcPlacementAsset... placements) {
        Map<String, NpcPlacementAsset> layer = new LinkedHashMap<>();
        for (NpcPlacementAsset placement : placements) {
            layer.put(placement.getId(), placement);
        }
        NpcPlacementConfig.getInstance().mergePackLayer(layer);
    }

    @Test
    void aPlacementWithNoWhereStandsInTheDefaultWorld() {
        WorldSelector where = PlacementWorlds.effectiveWhere(standing("guide_hub", "Guide", null, null));
        assertNotNull(where.match("default", "Default"));
        assertNull(where.match(TEMPLE_WORLD, "ForgottenTemple"));
    }

    @Test
    void everyPlacementOfACharacterGivesItsWhere() {
        load(standing("jack_temple", "Old_Jack", null, TEMPLE), standing("jack_hub", "Old_Jack", null, null));

        List<WorldSelector> wheres = PlacementWorlds.wheresOf("Old_Jack");

        assertEquals(2, wheres.size());
        assertTrue(wheres.stream().anyMatch(w -> w.match(TEMPLE_WORLD, "ForgottenTemple") != null));
        assertTrue(wheres.stream().anyMatch(w -> w.match("default", "Default") != null));
    }

    @Test
    void aSwitchedOffPlacementStandsNowhere() {
        load(standing("jack_temple", "Old_Jack", false, TEMPLE));
        assertTrue(PlacementWorlds.wheresOf("Old_Jack").isEmpty());
    }

    @Test
    void aCharacterNothingPlacesHasNoWorlds() {
        assertTrue(PlacementWorlds.wheresOf("Nobody").isEmpty());
        assertTrue(PlacementWorlds.wheresOf(null).isEmpty());
    }

    @Test
    void aCharacterIsFoundOnlyWhereItsPlacementsStandInThatWorld() {
        load(standing("jack_temple", "Old_Jack", null, TEMPLE));
        NpcPlacementPositionCache.record(TEMPLE_WORLD, "jack_temple", "spawn", 1, 2, 3);

        assertEquals(1, PlacementWorlds.positionsOf(TEMPLE_WORLD, "Old_Jack").size());
        assertTrue(PlacementWorlds.positionsOf("default", "Old_Jack").isEmpty());
    }
}
