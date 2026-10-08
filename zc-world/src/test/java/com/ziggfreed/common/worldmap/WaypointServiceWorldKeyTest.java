package com.ziggfreed.common.worldmap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.UUID;

import org.junit.jupiter.api.Test;

/**
 * A waypoint provider is registered once per live world, and a pinned instance that comes back
 * under the same name is a new world: World equality is by name, so the key is the world's uuid.
 */
class WaypointServiceWorldKeyTest {

    @Test
    void twoCopiesOfOneNamedInstanceAreTwoWorlds() {
        String name = "instance-forgotten-temple-goblins";
        assertNotEquals(WaypointService.worldKey(name, UUID.randomUUID()),
                WaypointService.worldKey(name, UUID.randomUUID()));
    }

    @Test
    void aWorldWithNoUuidIsKeyedByItsNameWithoutRegardToCase() {
        assertEquals(WaypointService.worldKey("Default", null), WaypointService.worldKey("default", null));
    }
}
