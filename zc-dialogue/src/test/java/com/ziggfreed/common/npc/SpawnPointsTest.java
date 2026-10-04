package com.ziggfreed.common.npc;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;

import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.math.vector.Transform;

/**
 * Reading a spawn point that Update 7 answers as a future, driven with plain futures standing in for a
 * provider's (a unit JVM has no world and no provider, and no engine {@code Transform} is built here,
 * only its type named): a landed point reads at once and a loading one reads nothing without being
 * waited for; a failure reads as its own cause; and the next step runs on the world thread once the
 * point lands, never on the thread that completed it, and never throws into that thread.
 */
class SpawnPointsTest {

    private static final Vector3dc SPAWN = new Vector3d(-207, 122, 47);

    @Test
    void aPointThatHasLandedReadsAtOnce() {
        assertEquals(SPAWN, SpawnPoints.now(CompletableFuture.completedFuture(SPAWN)));
        assertNull(SpawnPoints.failureOf(CompletableFuture.completedFuture(SPAWN)));
    }

    @Test
    void aPointStillOnItsWayReadsNothingAndIsNeverWaitedFor() {
        CompletableFuture<Vector3dc> loading = new CompletableFuture<>();

        assertNull(SpawnPoints.now(loading));
        assertNull(SpawnPoints.failureOf(loading));
        assertFalse(loading.isDone(), "reading it neither completed nor joined it");
    }

    @Test
    void aFailedCancelledOrEmptyQueryReadsNoPoint() {
        IllegalStateException cause = new IllegalStateException("the spawn column failed to load");
        CompletableFuture<Vector3dc> failed = CompletableFuture.failedFuture(cause);
        CompletableFuture<Vector3dc> cancelled = new CompletableFuture<>();
        cancelled.cancel(false);

        assertNull(SpawnPoints.now(failed));
        assertSame(cause, SpawnPoints.failureOf(failed), "the cause itself");
        assertNull(SpawnPoints.now(cancelled));
        assertInstanceOf(CancellationException.class, SpawnPoints.failureOf(cancelled));
        assertNull(SpawnPoints.now(CompletableFuture.completedFuture(null)), "a provider that answered no point");
        assertNull(SpawnPoints.failureOf(CompletableFuture.completedFuture(null)));
        assertNull(SpawnPoints.now(null));
        assertNull(SpawnPoints.failureOf(null));
    }

    @Test
    void theNextStepRunsOnTheWorldThreadOnceThePointLands() {
        HeldWorldThread world = new HeldWorldThread();
        CompletableFuture<Vector3dc> loading = new CompletableFuture<>();
        int[] ran = {0};

        SpawnPoints.whenLanded(loading, world, () -> ran[0]++);
        assertEquals(0, world.pending(), "nothing is queued while the point loads");
        loading.complete(SPAWN);
        assertEquals(0, ran[0], "never on the thread that completed the query (a chunk loader's)");
        assertEquals(1, world.runAll());
        assertEquals(1, ran[0]);
    }

    @Test
    void aPointThatAlreadyLandedStillContinuesOnTheWorldThread() {
        HeldWorldThread world = new HeldWorldThread();
        int[] ran = {0};

        SpawnPoints.whenLanded(CompletableFuture.completedFuture(SPAWN), world, () -> ran[0]++);
        assertEquals(0, ran[0], "not inline on the caller's thread either");
        assertEquals(1, world.runAll());
        assertEquals(1, ran[0]);
    }

    @Test
    void aFailedQueryContinuesToo() {
        HeldWorldThread world = new HeldWorldThread();
        CompletableFuture<Vector3dc> loading = new CompletableFuture<>();
        int[] ran = {0};

        SpawnPoints.whenLanded(loading, world, () -> ran[0]++);
        loading.completeExceptionally(new IllegalStateException("the spawn column failed to load"));
        assertEquals(1, world.runAll());
        assertEquals(1, ran[0], "the caller's step decides what a failure means");
    }

    @Test
    void aWorldThatRefusesTheStepOrAStepThatThrowsNeverReachesTheLoader() {
        CompletableFuture<Vector3dc> refused = new CompletableFuture<>();
        int[] ran = {0};
        SpawnPoints.whenLanded(refused, task -> {
            throw new RejectedExecutionException("the world is stopping");
        }, () -> ran[0]++);
        assertDoesNotThrow(() -> refused.complete(SPAWN));
        assertEquals(0, ran[0], "a refused step is dropped");

        HeldWorldThread world = new HeldWorldThread();
        CompletableFuture<Vector3dc> loading = new CompletableFuture<>();
        SpawnPoints.whenLanded(loading, world, () -> {
            throw new IllegalStateException("the next step failed");
        });
        assertDoesNotThrow(() -> loading.complete(SPAWN));
        assertDoesNotThrow(world::runAll, "the step's own throw stays inside its continuation");
    }

    @Test
    void askingAProviderThatThrowsAnswersAFailedQuery() {
        IllegalStateException cause = new IllegalStateException("the provider is broken");

        CompletableFuture<Vector3dc> query = SpawnPoints.positionOf(() -> {
            throw cause;
        });

        assertNotNull(query, "a throw is an answer, never a throw into the caller");
        assertSame(cause, SpawnPoints.failureOf(query));
    }

    @Test
    void aProviderAnsweringNoQueryAnswersNoneAndOneAnsweringNoPointLandsEmpty() {
        assertNull(SpawnPoints.positionOf(() -> null));

        CompletableFuture<Vector3dc> empty = SpawnPoints.positionOf(() -> CompletableFuture.completedFuture(null));
        assertNotNull(empty);
        assertTrue(empty.isDone());
        assertNull(SpawnPoints.now(empty));
        assertNull(SpawnPoints.failureOf(empty));
    }

    @Test
    void aProviderQueryStillLoadingPassesItsFailureThroughUnwrapped() {
        CompletableFuture<Transform> loading = new CompletableFuture<>();
        IllegalStateException cause = new IllegalStateException("the column load failed");

        CompletableFuture<Vector3dc> query = SpawnPoints.positionOf(() -> loading);
        assertNotNull(query);
        assertFalse(query.isDone());
        loading.completeExceptionally(cause);
        assertSame(cause, SpawnPoints.failureOf(query), "the position step adds no wrapper a caller would peel");
    }
}
