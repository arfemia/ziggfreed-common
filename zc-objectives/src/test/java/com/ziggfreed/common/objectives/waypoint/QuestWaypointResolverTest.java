package com.ziggfreed.common.objectives.waypoint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.ziggfreed.common.worldmap.Gateways;
import com.ziggfreed.common.worldmap.WaypointPosition;
import com.ziggfreed.common.worldmap.WaypointViewer;

/** A gateway key resolves to the nearest way in this world recorded, and to nothing in a world without one. */
class QuestWaypointResolverTest {

    @AfterEach
    void forget() {
        Gateways.forget("default");
    }

    @Test
    void aGatewayKeyResolvesToTheNearestRecordedWayInOfThatWorld() {
        Gateways.remember("default", Map.of("forgotten_temple_portal_enter", List.of(
                new WaypointPosition("far", 500, 64, 500), new WaypointPosition("near", -20, 64, 10))));
        QuestWaypointResolver resolver = new QuestWaypointResolver();
        String key = QuestWaypointTargets.GATEWAY_PREFIX + "forgotten_temple_portal_enter";

        assertEquals(List.of("near"), resolver.resolve("default", key, new WaypointViewer(0, 0))
                .stream().map(WaypointPosition::anchorKey).toList());
        assertTrue(resolver.resolve("orbis_two", key, new WaypointViewer(0, 0)).isEmpty());
    }
}
