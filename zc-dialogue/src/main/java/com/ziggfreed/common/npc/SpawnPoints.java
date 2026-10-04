package com.ziggfreed.common.npc;

import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.function.Supplier;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.joml.Vector3dc;

import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.spawn.ISpawnProvider;

/**
 * A world's spawn point, asked of its spawn provider without ever waiting for it on the world thread.
 *
 * <p><b>Why it is a future.</b> Update 7 answers a spawn point as a {@link CompletableFuture}
 * ({@code ISpawnProvider.getSpawnPointAsync}). A provider that fits its point to the ground
 * ({@code FitToHeightMapSpawnProvider}) loads the spawn column first, and the world thread is what
 * finishes that load, so joining the future there could stall the very tick it waits on. A point is
 * therefore either here now or on its way: {@link #now} reads one that has landed and never blocks, and
 * {@link #whenLanded} runs the caller's next step on the world thread once it lands, the way the engine
 * continues its own spawn-point reads ({@code thenAcceptAsync(..., world)}).
 *
 * <p>Nothing here throws: a provider that throws answers a failed query, and a failed, cancelled or
 * empty query reads as no point. What "no point" means (a fallback, a retry, a line in the log) is the
 * caller's call.
 */
public final class SpawnPoints {

    private SpawnPoints() {
    }

    /**
     * Ask {@code provider} for its spawn point in {@code world} for {@code uuid}, as the position the
     * point stands at. Null when there is no provider to ask (a world whose spawn provider has not
     * settled yet). Never blocks and never throws.
     */
    @Nullable
    public static CompletableFuture<Vector3dc> ask(@Nullable ISpawnProvider provider, @Nonnull World world,
            @Nonnull UUID uuid) {
        if (provider == null) {
            return null;
        }
        return positionOf(() -> provider.getSpawnPointAsync(world, uuid));
    }

    /**
     * The position a spawn-point query lands with. A query that throws as it is asked answers a failed
     * query, and a provider that answers no query at all answers null. Package-private for the test;
     * {@link #ask} is the live path through it.
     */
    @Nullable
    static CompletableFuture<Vector3dc> positionOf(@Nonnull Supplier<CompletableFuture<Transform>> query) {
        CompletableFuture<Transform> point;
        try {
            point = query.get();
        } catch (Throwable t) {
            return CompletableFuture.failedFuture(t);
        }
        if (point == null) {
            return null;
        }
        return point.<Vector3dc>thenApply(transform -> transform == null ? null : transform.getPosition());
    }

    /**
     * The point a query has already landed with; null while it is still on its way, when it failed or
     * was cancelled, and when it landed with no point. Never blocks, so it is safe on the world thread.
     */
    @Nullable
    public static Vector3dc now(@Nullable CompletableFuture<Vector3dc> query) {
        if (query == null || query.state() != Future.State.SUCCESS) {
            return null;
        }
        return query.resultNow();
    }

    /**
     * Why a query did not land: its own cause when it failed, a {@link CancellationException} when it was
     * cancelled, else null (still on its way, or landed). Never blocks.
     */
    @Nullable
    public static Throwable failureOf(@Nullable CompletableFuture<?> query) {
        if (query == null) {
            return null;
        }
        return switch (query.state()) {
            case FAILED -> query.exceptionNow();
            case CANCELLED -> new CancellationException("the spawn point query was cancelled");
            case RUNNING, SUCCESS -> null;
        };
    }

    /**
     * Run {@code next} on {@code worldThread} once {@code query} completes, landed or failed, and never on
     * the thread that completed it (a chunk loader's): a query that has already completed still goes
     * through {@code worldThread}. A world that refuses the task (it is stopping) drops it, and a
     * {@code next} that throws stays inside the continuation; neither reaches the thread that completed
     * the query.
     */
    public static void whenLanded(@Nonnull CompletableFuture<?> query, @Nonnull Executor worldThread,
            @Nonnull Runnable next) {
        query.whenCompleteAsync((value, failure) -> next.run(), worldThread);
    }
}
