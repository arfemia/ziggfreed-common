package com.ziggfreed.common.worldmap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import com.ziggfreed.common.worldmap.MapDiscovery.Discovery;
import com.ziggfreed.common.worldmap.MapDiscovery.Visibility;

/**
 * Deterministic unit tests for {@link MapDiscovery}'s PURE discovery state machine (no engine): the
 * {@code attach}/{@code detach}/{@code markersFor} paths need a {@code World}/{@code Player} and are
 * exercised in-game, but {@code register}/{@code discover}/{@code isDiscovered}/{@code revealWithin}
 * are plain logic.
 */
class MapDiscoveryTest {

    private static UUID id() {
        return UUID.randomUUID();
    }

    private static MapDiscovery tracker() {
        return new MapDiscovery("test_discovery");
    }

    @Test
    void discoverReturnsTrueOncePerPlayer() {
        MapDiscovery d = tracker();
        UUID a = id();
        d.register("shrine", "Temple_Gateway.png", 10, 64, 20, null, Visibility.PER_PLAYER);

        assertTrue(d.discover("shrine", a), "first discovery is new");
        assertFalse(d.discover("shrine", a), "a repeat discovery is not new");
        assertTrue(d.isDiscovered("shrine", a));
    }

    @Test
    void perPlayerVisibilityIsolatesViewers() {
        MapDiscovery d = tracker();
        UUID a = id();
        UUID b = id();
        d.register("shrine", "Temple_Gateway.png", 0, 64, 0, null, Visibility.PER_PLAYER);

        assertTrue(d.discover("shrine", a));
        assertTrue(d.isDiscovered("shrine", a), "the discoverer sees it");
        assertFalse(d.isDiscovered("shrine", b), "another player does not");
        assertTrue(d.discover("shrine", b), "b's own first discovery is new");
        assertTrue(d.isDiscovered("shrine", b));
    }

    @Test
    void sharedVisibilityRevealsToEveryone() {
        MapDiscovery d = tracker();
        UUID a = id();
        UUID b = id();
        d.register("shrine", "Temple_Gateway.png", 0, 64, 0, null, Visibility.SHARED);

        assertTrue(d.discover("shrine", a), "the first discoverer is new");
        assertTrue(d.isDiscovered("shrine", b), "everyone sees a shared POI once anyone finds it");
        assertFalse(d.discover("shrine", b), "a shared POI is only newly-discovered once, total");
    }

    @Test
    void discoverUnknownPoiIsNoOp() {
        MapDiscovery d = tracker();
        assertFalse(d.discover("missing", id()), "an unregistered id cannot be discovered");
    }

    @Test
    void reRegisterKeepsDiscoveryUnregisterForgetsIt() {
        MapDiscovery d = tracker();
        UUID a = id();
        d.register("shrine", "Temple_Gateway.png", 0, 64, 0, null, Visibility.PER_PLAYER);
        assertTrue(d.discover("shrine", a));

        d.updateIcon("shrine", "Campfire.png"); // lit-swap keeps the discovery
        d.register("shrine", "Campfire.png", 0, 64, 0, null, Visibility.PER_PLAYER);
        assertTrue(d.isDiscovered("shrine", a), "re-registering / icon-swapping keeps who discovered it");

        d.updateIcon("nope", "X.png"); // unknown id is a silent no-op
        d.unregister("shrine");
        assertFalse(d.isDiscovered("shrine", a), "unregister forgets the discovery");
    }

    @Test
    void revealWithinDiscoversInsideRadiusOnly() {
        MapDiscovery d = tracker();
        UUID near = id();
        UUID far = id();
        d.register("shrine", "Temple_Gateway.png", 0, 64, 0, null, Visibility.PER_PLAYER);

        Map<UUID, Vector3d> positions = Map.of(
                near, new Vector3d(3, 64, 4),   // dist 5 <= 10
                far, new Vector3d(0, 64, 50));  // dist 50 > 10
        List<Discovery> revealed = d.revealWithin(positions, 10.0);

        assertEquals(1, revealed.size(), "only the near player discovers it");
        assertEquals("shrine", revealed.get(0).poiId());
        assertEquals(near, revealed.get(0).playerUuid());
        assertTrue(d.isDiscovered("shrine", near));
        assertFalse(d.isDiscovered("shrine", far));

        assertTrue(d.revealWithin(positions, 10.0).isEmpty(), "an already-discovered POI is not re-returned");
    }

    @Test
    void revealWithinSharedRevealsToAll() {
        MapDiscovery d = tracker();
        UUID near = id();
        UUID other = id();
        d.register("shrine", "Temple_Gateway.png", 0, 64, 0, null, Visibility.SHARED);

        List<Discovery> revealed = d.revealWithin(Map.of(near, new Vector3d(1, 64, 1)), 10.0);
        assertEquals(1, revealed.size());
        assertTrue(d.isDiscovered("shrine", other), "proximity on a SHARED POI reveals it for everyone");
    }

    @Test
    void revealWithinNoOpOnEmptyOrZeroRadius() {
        MapDiscovery d = tracker();
        UUID a = id();
        d.register("shrine", "Temple_Gateway.png", 0, 64, 0, null, Visibility.PER_PLAYER);

        assertTrue(d.revealWithin(Map.of(), 10.0).isEmpty(), "no positions -> nothing");
        assertTrue(d.revealWithin(Map.of(a, new Vector3d(0, 64, 0)), 0.0).isEmpty(), "zero radius -> nothing");
        assertFalse(d.isDiscovered("shrine", a));
    }
}
