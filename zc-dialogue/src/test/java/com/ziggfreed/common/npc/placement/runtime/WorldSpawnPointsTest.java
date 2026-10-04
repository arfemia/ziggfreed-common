package com.ziggfreed.common.npc.placement.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.junit.jupiter.api.Test;

import com.ziggfreed.common.npc.HeldWorldThread;

/**
 * The WorldSpawn anchor's spawn-point read across sweep passes, driven with plain futures standing in
 * for a spawn provider's (a unit JVM has no world): a point that lands after a pass returns resolves on
 * the next pass, a query on its way is read again and never asked twice (the reconciler's retry path),
 * its landing wakes the world once on the world's own thread, and a failure is reported once, leaves
 * the anchor unresolved and wakes nothing.
 */
class WorldSpawnPointsTest {

    private static final Vector3dc SPAWN = new Vector3d(-207, 122, 47);

    /** A spawn provider answering the queued queries in order (null once they run out), counting each ask. */
    private static final class Provider implements Supplier<CompletableFuture<Vector3dc>> {
        private final Deque<CompletableFuture<Vector3dc>> answers = new ArrayDeque<>();
        private int asked;

        Provider answering(CompletableFuture<Vector3dc> answer) {
            answers.add(answer);
            return this;
        }

        int asked() {
            return asked;
        }

        @Override
        public CompletableFuture<Vector3dc> get() {
            asked++;
            return answers.poll();
        }
    }

    @Test
    void aPointThatLandsAfterThePassResolvesOnTheNextPass() {
        WorldSpawnPoints<String> points = new WorldSpawnPoints<>();
        HeldWorldThread world = new HeldWorldThread();
        CompletableFuture<Vector3dc> loading = new CompletableFuture<>();
        Provider provider = new Provider().answering(loading);
        List<Throwable> failures = new ArrayList<>();

        assertNull(points.read("default", provider, world, () -> { }, failures::add),
                "the spawn column is still loading, so this pass resolves nothing");
        loading.complete(SPAWN);
        assertEquals(SPAWN, points.read("default", provider, world, () -> { }, failures::add),
                "the next pass reads the point the first pass's query landed with");
        assertEquals(1, provider.asked(), "one query served both passes");
        assertTrue(failures.isEmpty());
    }

    @Test
    void aPassWhileTheQueryLoadsReadsItAgainAndNeverAsksTwice() {
        WorldSpawnPoints<String> points = new WorldSpawnPoints<>();
        HeldWorldThread world = new HeldWorldThread();
        Provider provider = new Provider().answering(new CompletableFuture<>());
        List<Throwable> failures = new ArrayList<>();

        for (int pass = 0; pass < 9; pass++) {
            assertNull(points.read("default", provider, world, () -> { }, failures::add), "pass " + pass);
        }
        assertEquals(1, provider.asked(), "the reconciler's retries read the one query on its way");
        assertEquals(0, world.pending(), "nothing wakes before it lands");
        assertTrue(failures.isEmpty());
    }

    @Test
    void aLandingWakesTheWorldOnceOnItsOwnThread() {
        WorldSpawnPoints<String> points = new WorldSpawnPoints<>();
        HeldWorldThread world = new HeldWorldThread();
        CompletableFuture<Vector3dc> loading = new CompletableFuture<>();
        Provider provider = new Provider().answering(loading);
        List<Throwable> failures = new ArrayList<>();
        int[] wakes = {0};
        Runnable wake = () -> wakes[0]++;

        points.read("default", provider, world, wake, failures::add);
        points.read("default", provider, world, wake, failures::add);
        loading.complete(SPAWN);
        assertEquals(0, wakes[0], "never on the thread that completed the query");
        assertEquals(1, world.runAll());
        assertEquals(1, wakes[0], "one wake per query, however many passes read it");
        assertEquals(SPAWN, points.read("default", provider, world, wake, failures::add),
                "and the pass the wake asks for finds the point");
        assertTrue(failures.isEmpty());
    }

    @Test
    void aPointAlreadyAtHandResolvesInTheSamePassAndTheNextPassAsksAfresh() {
        WorldSpawnPoints<String> points = new WorldSpawnPoints<>();
        HeldWorldThread world = new HeldWorldThread();
        Vector3dc moved = new Vector3d(0, 80, 0);
        Provider provider = new Provider()
                .answering(CompletableFuture.completedFuture(SPAWN))
                .answering(CompletableFuture.completedFuture(moved));
        List<Throwable> failures = new ArrayList<>();
        int[] wakes = {0};

        assertEquals(SPAWN, points.read("default", provider, world, () -> wakes[0]++, failures::add));
        assertEquals(moved, points.read("default", provider, world, () -> wakes[0]++, failures::add),
                "a spawn point that moved is picked up");
        assertEquals(2, provider.asked());
        assertEquals(0, world.pending(), "nothing waited, so nothing wakes");
        world.runAll();
        assertEquals(0, wakes[0]);
        assertTrue(failures.isEmpty());
    }

    @Test
    void aFailedQueryIsReportedOnceLeavesTheAnchorUnresolvedAndWakesNothing() {
        WorldSpawnPoints<String> points = new WorldSpawnPoints<>();
        HeldWorldThread world = new HeldWorldThread();
        IllegalStateException cause = new IllegalStateException("the spawn column failed to load");
        CompletableFuture<Vector3dc> loading = new CompletableFuture<>();
        Provider provider = new Provider().answering(loading).answering(CompletableFuture.failedFuture(cause));
        List<Throwable> failures = new ArrayList<>();
        int[] wakes = {0};

        assertNull(points.read("default", provider, world, () -> wakes[0]++, failures::add));
        loading.completeExceptionally(cause);
        world.runAll();
        assertEquals(0, wakes[0], "a failure is no reason to sweep the world again");
        assertNull(points.read("default", provider, world, () -> wakes[0]++, failures::add), "unresolved");
        assertNull(points.read("default", provider, world, () -> wakes[0]++, failures::add),
                "the next pass asks again, and fails again");
        assertEquals(2, provider.asked());
        assertEquals(List.of(cause), failures, "reported once, however often it fails");
    }

    @Test
    void aPointAfterAFailureArmsTheReportAgain() {
        WorldSpawnPoints<String> points = new WorldSpawnPoints<>();
        HeldWorldThread world = new HeldWorldThread();
        IllegalStateException first = new IllegalStateException("first failure");
        IllegalStateException second = new IllegalStateException("second failure");
        Provider provider = new Provider()
                .answering(CompletableFuture.failedFuture(first))
                .answering(CompletableFuture.completedFuture(SPAWN))
                .answering(CompletableFuture.failedFuture(second));
        List<Throwable> failures = new ArrayList<>();

        assertNull(points.read("default", provider, world, () -> { }, failures::add));
        assertEquals(SPAWN, points.read("default", provider, world, () -> { }, failures::add));
        assertNull(points.read("default", provider, world, () -> { }, failures::add));
        assertEquals(List.of(first, second), failures, "a point in between makes the next failure news again");
    }

    @Test
    void noProviderToAskIsNoPointAndNothingIsKept() {
        WorldSpawnPoints<String> points = new WorldSpawnPoints<>();
        Provider provider = new Provider();
        List<Throwable> failures = new ArrayList<>();

        assertNull(points.read("default", provider, new HeldWorldThread(), () -> { }, failures::add));
        assertNull(points.read("default", provider, new HeldWorldThread(), () -> { }, failures::add));
        assertEquals(2, provider.asked(), "nothing was kept, so each pass asks");
        assertTrue(failures.isEmpty());
    }

    @Test
    void aQueryThatLandsWithNoPointResolvesNothingAndWakesNothing() {
        WorldSpawnPoints<String> points = new WorldSpawnPoints<>();
        HeldWorldThread world = new HeldWorldThread();
        CompletableFuture<Vector3dc> loading = new CompletableFuture<>();
        Provider provider = new Provider().answering(loading);
        List<Throwable> failures = new ArrayList<>();
        int[] wakes = {0};

        assertNull(points.read("default", provider, world, () -> wakes[0]++, failures::add));
        loading.complete(null);
        world.runAll();
        assertEquals(0, wakes[0]);
        assertNull(points.read("default", provider, world, () -> wakes[0]++, failures::add));
        assertTrue(failures.isEmpty(), "no point is not a failure");
    }

    @Test
    void eachWorldKeepsItsOwnQueryAndForgettingOneDropsOnlyIt() {
        WorldSpawnPoints<String> points = new WorldSpawnPoints<>();
        HeldWorldThread world = new HeldWorldThread();
        CompletableFuture<Vector3dc> overworld = new CompletableFuture<>();
        Provider overworldProvider = new Provider().answering(overworld);
        Provider instanceProvider = new Provider()
                .answering(new CompletableFuture<>())
                .answering(new CompletableFuture<>());
        List<Throwable> failures = new ArrayList<>();

        points.read("default", overworldProvider, world, () -> { }, failures::add);
        points.read("instance-crypt", instanceProvider, world, () -> { }, failures::add);
        points.forget("instance-crypt");
        points.read("instance-crypt", instanceProvider, world, () -> { }, failures::add);
        assertEquals(2, instanceProvider.asked(), "a forgotten world asks afresh");

        overworld.complete(SPAWN);
        assertEquals(SPAWN, points.read("default", overworldProvider, world, () -> { }, failures::add),
                "the other world's query was untouched");
        assertEquals(1, overworldProvider.asked());
        assertTrue(failures.isEmpty());
    }
}
