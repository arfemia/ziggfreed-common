package com.ziggfreed.common.npc.placement.runtime;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Supplier;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.joml.Vector3dc;

import com.ziggfreed.common.npc.SpawnPoints;

/**
 * The world spawn point a {@code WorldSpawn} anchor stands on, read across sweep passes without any
 * pass waiting for it ({@link SpawnPoints} says why a spawn point is a future on Update 7).
 *
 * <p>One query per key is kept on its way at a time. What a pass reads:
 * <ul>
 *   <li><b>A point the query landed with.</b> The pass uses it and the query is forgotten, so the next
 *       pass asks afresh and a spawn point that moved is picked up, as before.</li>
 *   <li><b>Nothing, while the query is on its way.</b> The anchor resolves nothing this pass, which is
 *       the reconciler's retry signal; the next pass reads the SAME query instead of asking again; and
 *       {@code wake} runs on {@code worldThread} once it lands with a point, so the placement goes in
 *       even after the reconciler's retry budget is spent.</li>
 *   <li><b>Nothing, when the query failed or landed with no point.</b> A failure reaches
 *       {@code onFailure} once per key until a query for that key lands with a point, and nothing wakes:
 *       a provider that keeps failing must not keep the world sweeping. The next pass asks again.</li>
 * </ul>
 *
 * <p>Generic in what names a world, so the rules are tested without one; {@link PlacementAnchors} keys
 * it by the {@code World} and drops a removed world through {@code WorldEvictors}.
 */
final class WorldSpawnPoints<K> {

    /** The query still on its way per key; a landed query is removed by the pass that reads it. */
    private final Map<K, CompletableFuture<Vector3dc>> onTheirWay = new ConcurrentHashMap<>();

    /** Keys whose failure was reported since their last point. */
    private final Set<K> failureReported = ConcurrentHashMap.newKeySet();

    /**
     * The point for {@code key} this pass, or null: still on its way, failed, or none to be had.
     *
     * @param ask         asks the spawn provider afresh; answers null when there is none to ask
     * @param worldThread where {@code wake} runs once a query that was on its way lands with a point
     * @param wake        what a landing asks for (a sweep of the world)
     * @param onFailure   hears a failed query's cause, once per key until a point lands
     */
    @Nullable
    Vector3dc read(@Nonnull K key, @Nonnull Supplier<CompletableFuture<Vector3dc>> ask,
            @Nonnull Executor worldThread, @Nonnull Runnable wake, @Nonnull Consumer<Throwable> onFailure) {
        CompletableFuture<Vector3dc> query = onTheirWay.get(key);
        if (query == null) {
            query = ask.get();
            if (query == null) {
                return null;
            }
            if (!query.isDone()) {
                if (onTheirWay.putIfAbsent(key, query) == null) {
                    CompletableFuture<Vector3dc> landing = query;
                    SpawnPoints.whenLanded(landing, worldThread, () -> {
                        if (SpawnPoints.now(landing) != null) {
                            wake.run();
                        }
                    });
                }
                return null;
            }
        } else if (!query.isDone()) {
            return null;
        } else {
            onTheirWay.remove(key, query);
        }
        Throwable failure = SpawnPoints.failureOf(query);
        if (failure != null) {
            if (failureReported.add(key)) {
                onFailure.accept(failure);
            }
            return null;
        }
        Vector3dc point = SpawnPoints.now(query);
        if (point != null) {
            failureReported.remove(key);
        }
        return point;
    }

    /** Forget {@code key}'s query and its failure report (the world went away). */
    void forget(@Nonnull K key) {
        onTheirWay.remove(key);
        failureReported.remove(key);
    }
}
