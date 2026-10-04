package com.ziggfreed.common.npc;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.junit.jupiter.api.Test;

/**
 * When the auto-spawn places its NPC, driven through its package-private seam with plain futures for
 * the spawn point and a held executor for the world thread (a unit JVM has no world): at once when the
 * point is at hand, on the world thread once it lands when it is still loading, once for two calls made
 * while one point loads, and never when the point fails or lands empty.
 */
class NpcAutoSpawnTest {

    private static final Vector3dc SPAWN = new Vector3d(-207, 122, 47);

    @Test
    void aSpawnPointAlreadyAtHandPlacesDuringTheCall() {
        HeldWorldThread world = new HeldWorldThread();
        List<Vector3dc> placed = new ArrayList<>();
        List<Throwable> failures = new ArrayList<>();

        NpcAutoSpawn.placeWhenLanded(CompletableFuture.completedFuture(SPAWN), world, () -> false,
                placed::add, failures::add);

        assertEquals(List.of(SPAWN), placed, "placed before the call returns");
        assertEquals(0, world.pending(), "nothing left waiting on the world thread");
        assertTrue(failures.isEmpty());
    }

    @Test
    void aSpawnPointStillLoadingPlacesOnTheWorldThreadOnceItLands() {
        HeldWorldThread world = new HeldWorldThread();
        CompletableFuture<Vector3dc> loading = new CompletableFuture<>();
        List<Vector3dc> placed = new ArrayList<>();
        List<Throwable> failures = new ArrayList<>();

        NpcAutoSpawn.placeWhenLanded(loading, world, () -> false, placed::add, failures::add);
        assertTrue(placed.isEmpty(), "nothing is placed while the spawn column loads");
        loading.complete(SPAWN);
        assertTrue(placed.isEmpty(), "never on the thread that completed the query");
        assertEquals(1, world.runAll());
        assertEquals(List.of(SPAWN), placed);
        assertTrue(failures.isEmpty());
    }

    @Test
    void twoCallsWhileOnePointLoadsPlaceOneNpc() {
        HeldWorldThread world = new HeldWorldThread();
        CompletableFuture<Vector3dc> first = new CompletableFuture<>();
        CompletableFuture<Vector3dc> second = new CompletableFuture<>();
        boolean[] recorded = {false};
        List<Vector3dc> placed = new ArrayList<>();
        List<Throwable> failures = new ArrayList<>();
        Consumer<Vector3dc> place = point -> {
            placed.add(point);
            recorded[0] = true;
        };

        NpcAutoSpawn.placeWhenLanded(first, world, () -> recorded[0], place, failures::add);
        NpcAutoSpawn.placeWhenLanded(second, world, () -> recorded[0], place, failures::add);
        first.complete(SPAWN);
        second.complete(SPAWN);
        world.runAll();

        assertEquals(1, placed.size(), "the second landing finds the first NPC already recorded");
        assertTrue(failures.isEmpty());
    }

    @Test
    void aFailedSpawnPointPlacesNothingAndSaysWhy() {
        HeldWorldThread world = new HeldWorldThread();
        CompletableFuture<Vector3dc> loading = new CompletableFuture<>();
        IllegalStateException cause = new IllegalStateException("the spawn column failed to load");
        List<Vector3dc> placed = new ArrayList<>();
        List<Throwable> failures = new ArrayList<>();

        NpcAutoSpawn.placeWhenLanded(loading, world, () -> false, placed::add, failures::add);
        loading.completeExceptionally(cause);
        world.runAll();

        assertTrue(placed.isEmpty());
        assertEquals(List.of(cause), failures);
    }

    @Test
    void aSpawnPointLandingWithNoPositionPlacesNothing() {
        HeldWorldThread world = new HeldWorldThread();
        List<Vector3dc> placed = new ArrayList<>();
        List<Throwable> failures = new ArrayList<>();

        NpcAutoSpawn.placeWhenLanded(CompletableFuture.completedFuture(null), world, () -> false,
                placed::add, failures::add);

        assertTrue(placed.isEmpty());
        assertTrue(failures.isEmpty(), "no point is not a failure");
    }

    @Test
    void aPlacementThatThrowsNeverReachesTheCallerOrTheLoader() {
        HeldWorldThread world = new HeldWorldThread();
        CompletableFuture<Vector3dc> loading = new CompletableFuture<>();
        List<Throwable> failures = new ArrayList<>();
        Consumer<Vector3dc> broken = point -> {
            throw new IllegalStateException("the role is not registered");
        };

        assertDoesNotThrow(() -> NpcAutoSpawn.placeWhenLanded(CompletableFuture.completedFuture(SPAWN), world,
                () -> false, broken, failures::add));
        NpcAutoSpawn.placeWhenLanded(loading, world, () -> false, broken, failures::add);
        assertDoesNotThrow(() -> loading.complete(SPAWN));
        assertDoesNotThrow(world::runAll);
        assertTrue(failures.isEmpty(), "a placement that throws is logged, not a spawn-point failure");
    }
}
