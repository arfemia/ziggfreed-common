package com.ziggfreed.common.worldmap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.ziggfreed.common.codec.Vec3;
import com.ziggfreed.common.world.WorldSelector;

/**
 * Where the way into another world stands: which gateways lead from here into a character's worlds,
 * which recorded block markers and fixed spots are its ways in, and which one a viewer is pointed at.
 */
class GatewaysTest {

    private static final WorldSelector IN_THE_TEMPLE = WorldSelector.of(null, new String[]{"ForgottenTemple"}, null);
    private static final WorldSelector IN_DEFAULT = WorldSelector.of(new String[]{"default"}, null, null);
    private static final String PORTAL_MARKER = "server.items.Portal.name";

    @AfterEach
    void forgetWorlds() {
        Gateways.forget("default");
        Gateways.forget("orbis_two");
    }

    private static GatewayAsset temple(Boolean enabled, WorldSelector where) {
        return GatewayAsset.of("Forgotten_Temple_Portal_Enter", enabled, where,
                GatewayAsset.Into.of(null, "ForgottenTemple"), new String[]{"Portal"}, null);
    }

    @Test
    void aGatewayLeadsIntoTheWorldsATargetsWhereMatches() {
        List<GatewayAsset> found = Gateways.leadingInto(List.of(temple(null, null)), List.of(IN_THE_TEMPLE),
                "default", "Default");
        assertEquals(List.of("Forgotten_Temple_Portal_Enter"), found.stream().map(GatewayAsset::getId).toList());
    }

    @Test
    void aGatewayLeadingElsewhereIsNotOffered() {
        GatewayAsset arena = GatewayAsset.of("Arena", null, null, GatewayAsset.Into.of(null, "Arena"),
                new String[]{"Portal"}, null);
        assertTrue(Gateways.leadingInto(List.of(arena), List.of(IN_THE_TEMPLE), "default", "Default").isEmpty());
    }

    @Test
    void aGatewayStandsOnlyInTheWorldsItsWhereMatches() {
        GatewayAsset onlyInDefault = temple(null, IN_DEFAULT);
        assertEquals(1, Gateways.leadingInto(List.of(onlyInDefault), List.of(IN_THE_TEMPLE), "default", "Default").size());
        assertTrue(Gateways.leadingInto(List.of(onlyInDefault), List.of(IN_THE_TEMPLE), "orbis_two", "Default").isEmpty());
    }

    @Test
    void aSwitchedOffGatewayOrOneLeadingNowhereIsNeverOffered() {
        GatewayAsset off = temple(false, null);
        GatewayAsset nowhere = GatewayAsset.of("Nowhere", null, null, null, new String[]{"Portal"}, null);
        assertTrue(Gateways.leadingInto(List.of(off, nowhere), List.of(IN_THE_TEMPLE), "default", "Default").isEmpty());
    }

    @Test
    void everyRecordedCopyOfTheGatewaysBlockIsAWayInAndFixedPositionsJoinThem() {
        GatewayAsset gateway = GatewayAsset.of("Forgotten_Temple_Portal_Enter", null, null,
                GatewayAsset.Into.of(null, "ForgottenTemple"), new String[]{"Portal"},
                new Vec3[]{Vec3.of(10.0, 64.0, 20.0)});

        Map<String, List<WaypointPosition>> byGateway = Gateways.positionsByGateway(List.of(gateway),
                block -> block.equals("Portal") ? PORTAL_MARKER : null,
                List.of(new Gateways.BlockMarker(100, 70, -30, PORTAL_MARKER),
                        new Gateways.BlockMarker(5, 5, 5, "server.items.Something_Else.name")));

        List<WaypointPosition> ways = byGateway.get("forgotten_temple_portal_enter");
        assertEquals(2, ways.size(), "the other marker is not this gateway's");
        assertEquals(100.5, ways.get(0).x());
        assertEquals(70.0, ways.get(0).y());
        assertEquals(-29.5, ways.get(0).z());
        assertEquals(10.0, ways.get(1).x());
        assertEquals(20.0, ways.get(1).z());
    }

    @Test
    void aBlockThatDrawsNoMarkerFindsNothing() {
        assertTrue(Gateways.positionsByGateway(List.of(temple(null, null)), block -> null,
                List.of(new Gateways.BlockMarker(1, 2, 3, PORTAL_MARKER))).isEmpty());
    }

    @Test
    void theNearestWayInIsTheOnePointedAt() {
        List<WaypointPosition> ways = List.of(new WaypointPosition("far", 1000, 64, 1000),
                new WaypointPosition("near", 40, 64, -30), new WaypointPosition("mid", -300, 64, 0));
        assertEquals(List.of(ways.get(1)), Gateways.nearest(ways, new WaypointViewer(0, 0)));
    }

    @Test
    void withNoViewerPositionEveryWayInIsPointedAt() {
        List<WaypointPosition> ways = List.of(new WaypointPosition("a", 1, 64, 1), new WaypointPosition("b", 2, 64, 2));
        assertEquals(ways, Gateways.nearest(ways, null));
    }

    @Test
    void whatWasIndexedForAWorldIsReadBackByThatWorldAlone() {
        WaypointPosition way = new WaypointPosition("1,2,3", 1.5, 2, 3.5);
        Gateways.remember("Default", Map.of("Forgotten_Temple_Portal_Enter", List.of(way)));

        assertEquals(List.of(way), Gateways.positions("default", "forgotten_temple_portal_enter"), "both keys fold case");
        assertTrue(Gateways.positions("orbis_two", "forgotten_temple_portal_enter").isEmpty());
        Gateways.forget("default");
        assertTrue(Gateways.positions("default", "forgotten_temple_portal_enter").isEmpty());
    }
}
