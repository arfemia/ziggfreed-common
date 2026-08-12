package com.ziggfreed.common.worldmap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/**
 * The waypoint mechanism without a world: source registration and merge order, the snapshot diff a
 * refresh produces, and the per-world resolution that decides which markers a viewer actually gets.
 *
 * <p>The seam is deliberately exercised the way an adapter over an unrelated position lookup would
 * use it - a lambda resolver keyed by world name - so a new kind of place needs no change here.
 */
class WaypointSnapshotsTest {

    private static final UUID VIEWER = new UUID(0, 1);
    private static final UUID OTHER = new UUID(0, 2);

    private final List<String> warnings = new ArrayList<>();

    private WaypointSnapshots snapshots() {
        return new WaypointSnapshots("yourmod:waypoints", "Coordinate.png", warnings::add);
    }

    /** A resolver over a plain table: world name -> position key -> the positions there. */
    private static WaypointPositionResolver tableResolver(
            Map<String, Map<String, List<WaypointPosition>>> table) {
        return (worldName, positionKey) -> table
                .getOrDefault(worldName, Map.of())
                .getOrDefault(positionKey, List.of());
    }

    @Test
    void refreshMergesEverySourceAndKeepsTheFirstOfARepeatedId() {
        WaypointSnapshots snapshots = snapshots();
        snapshots.addSource(viewer -> List.of(WaypointTarget.of("forge", null)));
        snapshots.addSource(viewer -> List.of(
                WaypointTarget.of("forge", null),
                WaypointTarget.of("well", null)));

        assertEquals(2, snapshots.refresh(VIEWER), "a repeated id contributes once");
        assertEquals(List.of("forge", "well"),
                snapshots.targets(VIEWER).stream().map(WaypointTarget::id).toList(),
                "registration order is the precedence");
    }

    @Test
    void registeringTheSameSourceTwiceIsANoOp() {
        WaypointSnapshots snapshots = snapshots();
        WaypointTargetSource source = viewer -> List.of(WaypointTarget.of("forge", null));
        snapshots.addSource(source);
        snapshots.addSource(source);

        assertEquals(1, snapshots.sourceCount());
        assertTrue(snapshots.removeSource(source));
        assertFalse(snapshots.removeSource(source));
    }

    @Test
    void aRefreshWithNothingToPointAtForgetsTheViewer() {
        WaypointSnapshots snapshots = snapshots();
        snapshots.set(VIEWER, List.of(WaypointTarget.of("forge", null)));
        assertEquals(1, snapshots.viewerCount());

        assertEquals(0, snapshots.refresh(VIEWER), "no sources means nowhere to point");
        assertEquals(0, snapshots.viewerCount(), "an empty snapshot is dropped, not stored empty");
        assertTrue(snapshots.targets(VIEWER).isEmpty());
    }

    @Test
    void aThrowingSourceIsReportedAndTheRestStillContribute() {
        WaypointSnapshots snapshots = snapshots();
        snapshots.addSource(viewer -> {
            throw new IllegalStateException("boom");
        });
        snapshots.addSource(viewer -> List.of(WaypointTarget.of("well", null)));

        assertEquals(1, snapshots.refresh(VIEWER));
        assertEquals(List.of("well"), snapshots.targets(VIEWER).stream().map(WaypointTarget::id).toList());
        assertEquals(1, warnings.size(), "the failure is reported exactly once");
    }

    @Test
    void viewersDoNotShareSnapshots() {
        WaypointSnapshots snapshots = snapshots();
        snapshots.addSource(viewer -> VIEWER.equals(viewer)
                ? List.of(WaypointTarget.of("forge", null))
                : List.of());

        snapshots.refresh(VIEWER);
        snapshots.refresh(OTHER);
        assertEquals(1, snapshots.targets(VIEWER).size());
        assertTrue(snapshots.targets(OTHER).isEmpty());

        snapshots.clear(VIEWER);
        assertTrue(snapshots.targets(VIEWER).isEmpty());
    }

    @Test
    void aTargetOnlyProducesMarkersInTheWorldItResolvesIn() {
        WaypointSnapshots snapshots = snapshots();
        snapshots.set(VIEWER, List.of(WaypointTarget.of("forge", null)));
        WaypointPositionResolver resolver = tableResolver(Map.of(
                "overworld", Map.of("forge", List.of(new WaypointPosition("a", 10, 64, 20)))));

        assertEquals(1, snapshots.markerSpecsFor("overworld", VIEWER, resolver).size());
        assertTrue(snapshots.markerSpecsFor("caverns", VIEWER, resolver).isEmpty(),
                "a place that is not in this world contributes no marker here");
    }

    @Test
    void severalPositionsForOnePlaceStaySeparateMarkers() {
        WaypointSnapshots snapshots = snapshots();
        snapshots.set(VIEWER, List.of(WaypointTarget.of("forge", null)));
        WaypointPositionResolver resolver = tableResolver(Map.of("overworld", Map.of("forge", List.of(
                new WaypointPosition("copy_a", 10, 64, 20),
                new WaypointPosition("copy_b", 90, 64, 30)))));

        List<WaypointSnapshots.MarkerSpec> specs = snapshots.markerSpecsFor("overworld", VIEWER, resolver);
        assertEquals(2, specs.size(), "two live copies of one place are two markers");
        assertEquals(List.of("yourmod:waypoints:forge:copy_a", "yourmod:waypoints:forge:copy_b"),
                specs.stream().map(WaypointSnapshots.MarkerSpec::id).toList(),
                "the marker id carries the provider key, the target, and the anchor");
        assertEquals(10d, specs.get(0).x());
        assertEquals(90d, specs.get(1).x());
    }

    @Test
    void aTargetsOwnIconWinsOverTheDefault() {
        WaypointSnapshots snapshots = snapshots();
        snapshots.set(VIEWER, List.of(
                WaypointTarget.of("forge", null),
                new WaypointTarget("well", "well", null, "Home.png")));
        WaypointPositionResolver resolver = tableResolver(Map.of("overworld", Map.of(
                "forge", List.of(new WaypointPosition("a", 1, 2, 3)),
                "well", List.of(new WaypointPosition("a", 4, 5, 6)))));

        List<WaypointSnapshots.MarkerSpec> specs = snapshots.markerSpecsFor("overworld", VIEWER, resolver);
        assertEquals("Coordinate.png", specs.get(0).icon());
        assertEquals("Home.png", specs.get(1).icon());
    }

    @Test
    void aTargetMayNameAPlaceKeyDifferentFromItsOwnId() {
        WaypointSnapshots snapshots = snapshots();
        snapshots.set(VIEWER, List.of(WaypointTarget.of("step_one", "forge", null)));
        WaypointPositionResolver resolver = tableResolver(Map.of(
                "overworld", Map.of("forge", List.of(new WaypointPosition("a", 1, 2, 3)))));

        List<WaypointSnapshots.MarkerSpec> specs = snapshots.markerSpecsFor("overworld", VIEWER, resolver);
        assertEquals(1, specs.size());
        assertEquals("yourmod:waypoints:step_one:a", specs.get(0).id(),
                "the marker id is built from the target id, while the lookup used the place key");
    }

    @Test
    void aThrowingResolverIsReportedAndTheOtherTargetsStillResolve() {
        WaypointSnapshots snapshots = snapshots();
        snapshots.set(VIEWER, List.of(
                WaypointTarget.of("broken", null),
                WaypointTarget.of("forge", null)));
        WaypointPositionResolver resolver = (worldName, positionKey) -> {
            if ("broken".equals(positionKey)) {
                throw new IllegalStateException("boom");
            }
            return List.of(new WaypointPosition("a", 1, 2, 3));
        };

        assertEquals(1, snapshots.markerSpecsFor("overworld", VIEWER, resolver).size());
        assertEquals(1, warnings.size());
    }
}
